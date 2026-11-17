"""Safety checks for the destructive debug-only capture cleanup tool."""

import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import cleanup_captures as cleanup


class CleanupSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.media = self.root / "evidence.mp4"
        self.media.write_bytes(b"archived test footage")
        self.sha = hashlib.sha256(self.media.read_bytes()).hexdigest()
        self.name = "files/capture-prototype/completed/12345678-1234-1234-1234-123456789abc.mp4"
        self.data = {"schema": 1, "serial": "test-device", "package": cleanup.PACKAGE,
                     "files": [{"path": self.name, "sha256": self.sha, "archive": self.media.name}]}
        self.manifest = self.root / "manifest.json"

    def read(self):
        self.manifest.write_text(json.dumps(self.data))
        return cleanup.read_manifest(self.manifest, "test-device")

    def tar(self, members):
        archive = self.root / "backup.tar"
        with tarfile.open(archive, "w") as out:
            for name, content in members:
                info = tarfile.TarInfo(name)
                info.size = len(content)
                out.addfile(info, io.BytesIO(content))
        return archive

    def test_only_explicit_locally_archived_capture_is_accepted(self):
        self.assertEqual({self.name: self.sha}, self.read())

    def test_projects_traversal_globs_and_foreign_paths_are_rejected(self):
        for name in ["Projects/source.mp4", "RecentlyDeleted/source.mp4",
                     "files/capture-prototype/completed/../../Projects/source.mp4",
                     "files/capture-prototype/completed/*.mp4", "/" + self.name,
                     self.name + "\n", "files/other/12345678-1234-1234-1234-123456789abc.mp4"]:
            with self.subTest(name=name):
                self.data["files"][0]["path"] = name
                with self.assertRaises(ValueError):
                    self.read()

    def test_device_and_package_must_match(self):
        for key in ["serial", "package"]:
            original = self.data[key]
            self.data[key] = "other"
            with self.assertRaises(ValueError):
                self.read()
            self.data[key] = original

    def test_missing_or_changed_local_evidence_is_rejected(self):
        self.media.write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.read()
        self.media.unlink()
        with self.assertRaises(ValueError):
            self.read()

    def test_duplicate_and_empty_manifest_are_rejected(self):
        self.data["files"] *= 2
        with self.assertRaises(ValueError):
            self.read()
        self.data["files"] = []
        with self.assertRaises(ValueError):
            self.read()

    def test_backup_must_include_user_files_as_well_as_cleanup_targets(self):
        archive = self.tar([("./" + self.name, self.media.read_bytes())])
        # A backup of just the files to remove must not pass when user data exists.
        with self.assertRaises(RuntimeError):
            cleanup.verify_backup(archive, {self.name: self.sha, "Projects/project.json": "0" * 64})

    def test_complete_backup_verifies_content(self):
        user = b'{"name":"keep this project"}'
        expected = {self.name: self.sha, "Projects/project.json": hashlib.sha256(user).hexdigest()}
        archive = self.tar([("./" + self.name, self.media.read_bytes()), ("./Projects/project.json", user)])
        cleanup.verify_backup(archive, expected)
        expected["Projects/project.json"] = "0" * 64
        with self.assertRaises(RuntimeError):
            cleanup.verify_backup(archive, expected)

    def test_backup_traversal_and_duplicate_members_are_rejected(self):
        for members in [[("../outside", b"x")], [("/outside", b"x")],
                        [("./same", b"x"), ("same", b"x")]]:
            with self.subTest(members=members):
                with self.assertRaises(ValueError):
                    cleanup.verify_backup(self.tar(members), {})

    def test_running_or_disconnected_device_is_rejected(self):
        device = cleanup.Device("adb", "test-device")
        for status, stdout, stderr in [(0, b"1234\n", b""), (1, b"", b"device offline")]:
            with self.subTest(status=status, stdout=stdout, stderr=stderr):
                with patch("cleanup_captures.subprocess.run") as run:
                    run.return_value.returncode = status
                    run.return_value.stdout = stdout
                    run.return_value.stderr = stderr
                    with self.assertRaises(RuntimeError):
                        device.stopped()


if __name__ == "__main__":
    unittest.main()
