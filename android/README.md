# Android capture prototype

Implementazione della fase 0 del [piano Android](../docs/ANDROID-PLAN.md), nel fork
`ecamuto/YouRecApp`. Versione **0.3.0-prototype**, launcher **Camera Lab**,
application ID `it.ecamuto.yourec.android.prototype`. Il progetto iOS resta nella struttura originale.

Il modulo Kotlin/Compose usa **Camera2, MediaCodec e AudioRecord indipendente**.
La versione 0.3 aggiunge un compositore EGL/GLES persistente per cambiare camera
senza chiudere audio, encoder o muxer: durante la riapertura il file mantiene
l'ultima immagine, dichiarandolo nella UI. Il cambio è stato collaudato sul Samsung
SM-A256B in verticale e orizzontale, partendo dalle due camere. Durante il passaggio
il video mostra un fermo immagine di circa un secondo. Misure e limiti:
[cambio camera](../docs/ANDROID-SWITCH.md).

Funzioni implementate:

- Android 13/API 33 minimo; compilazione e target SDK 36.
- Camera singola, frontale/posteriore selezionabile anche durante la ripresa e la pausa, H.264 1920×1080 SDR e AAC stereo 48 kHz.
- 30 fps richiesti; il frame rate effettivo dipende dal dispositivo e dalla scena.
- Registra, pausa, riprendi, ferma; timeline comune per rimuovere dalle due tracce lo stesso intervallo di pausa.
- Al resume si attende un keyframe prima di ammettere nuovamente video e PCM; l'audio in pausa viene escluso prima della codifica AAC.
- Orientamento bloccato durante il take; preview 16:9 adattata allo spazio disponibile, selfie specchiato soltanto in anteprima.
- Stop entrando in background; schermo acceso durante la ripresa; finalizzazione asincrona.
- Libreria persistente e lettore locale; file pubblicati dopo controllo delle tracce video/audio e rename atomico.
- Riprese incomplete conservate separatamente, senza pulizia automatica.

Il percorso GPU 0.3 è verificato nelle prove brevi sul Samsung, con i limiti audio riportati nel rapporto. Non è una versione pubblicabile o il completamento della fase 0.
Risultati, misure e limiti: [motore 0.2](../docs/ANDROID-CAPTURE.md).
Il [rapporto QA](../docs/ANDROID-QA.md) conserva anche le prove del precedente backend CameraX.

## Compilare

Servono JDK 17 o 21, Android SDK Platform 36 e accesso ai repository Google/Maven Central.
Aprire **questa cartella `android/`** come progetto in Android Studio, oppure:

```sh
cd android
export JAVA_HOME=/percorso/del/jdk
export ANDROID_HOME=/percorso/del/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

In alternativa a `ANDROID_HOME`, creare `android/local.properties` con `sdk.dir=/percorso/del/sdk`.
Il file è escluso da Git. Gradle 8.13 è incluso con checksum della distribuzione;
AGP, Kotlin e Compose BOM sono fissati alla baseline scelta. CameraX non è più una dipendenza.

APK: `app/build/outputs/apk/debug/app-debug.apk`. Su un telefono collegato e autorizzato:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -n it.ecamuto.yourec.android.prototype/it.ecamuto.yourec.android.MainActivity
```

Aggiornare in place: disinstallare o cancellare i dati fa perdere le riprese locali.

## Archiviazione e compatibilità

I file sono privati, in `files/capture-prototype/`:

```text
pending/      # file in scrittura, interrotti o falliti; conservati
completed/    # file finalizzati con tracce audio/video leggibili
```

La libreria viene ricostruita alla riapertura. Non vengono creati documenti YouRec o manifest iOS parziali.
Nessun permesso rete/archiviazione; nessuna pubblicazione in galleria. Backup automatico disabilitato;
backup/esportazione dall'interfaccia non ancora disponibili. Non usare il prototipo per riprese insostituibili.

Questa versione richiede esplicitamente camera con timestamp REALTIME, uscita 1080p per preview e codec,
encoder AVC/AAC e microfono stereo 48 kHz. Se la combinazione non funziona, segnala errore;
non introduce un fallback silenzioso. API 33 da sola non garantisce questi requisiti.
Silenziamento del microfono o discontinuità PCM rilevate interrompono la ripresa con errore.
La presenza delle tracce non sostituisce una decodifica integrale né prova il sincronismo A/V assoluto.

Camera, microfono ed encoder restano attivi durante la pausa: i campioni in pausa non entrano nel file,
ma consumi e termica devono ancora essere ottimizzati e misurati. Il muxer MP4 non è frammentato:
conservare un file interrotto non significa poterlo recuperare dopo crash.

Non sono implementati append, doppia camera, HDR/4K, effetti, editor,
condivisione, import progetti iOS, modelli ML e recovery dei file incompleti.

## Verifica

Build debug, **9 test JVM** e lint completati: 0 errori, 6 avvisi sulle versioni della toolchain/dipendenze.
I test coprono il clock comune, tagli PCM ai confini delle pause e conservazione/pubblicazione dei file.
Le prove del backend 0.2 coprono le due camere, verticale/orizzontale, pause multiple,
stop da pausa, background, nuove riprese e lettore. Sul percorso GPU 0.3 sono provati
cambio live/in pausa, orientamento e mirror, stop/Home durante cambio, doppio tocco,
riapertura e file decodificabili. Verificati anche spegnimento schermo (file salvato)
e force-stop durante il take (pending conservato ma non riproducibile), avviso alla
riapertura e nuova registrazione. Dettagli: [interruzioni](../docs/ANDROID-INTERRUPTIONS.md).
Take lunghi con misura di drift, interruzioni audio, recovery e secondo OEM restano aperti.

Controllo riproducibile di un file locale con Python 3, FFmpeg e FFprobe:

```sh
python3 tools/check_capture.py ripresa.mp4 --decode --max-video-step-ms 60 --max-audio-step-ms 23 --output report.json
```

Le soglie sono quelle delle prove brevi a 30 fps/AAC 48 kHz, non un criterio universale per ogni formato.
Il controllo rileva gap/PTS e problemi di decodifica, non il lip-sync o la qualità percepita.
Per una prova con tono costante riprodotto fisicamente durante tutto il take, aggiungere
`--continuous-tone-hz 700`: controlla finestre da 20 ms sui due canali. Non applicare
questo criterio a parlato ordinario o a un tono interrotto volontariamente.

## Pulizia dei test su build debug

`tools/cleanup_captures.py` rimuove solo i file di test elencati in un manifest
esplicito. Non è un comando per svuotare la libreria. Richiede app già ferma da
uno stato idle verificato e una copia locale con checksum corrispondente per ogni
file. Con `--apply` salva e verifica prima tutto il contenitore privato; dopo la
cancellazione controlla immediatamente ogni dato rimanente, prima di riaprire l'app.
I backup contengono video privati: conservarli fuori dal repository, ad esempio
sotto `.benchmarks/` nella radice.

Manifest JSON (un elemento per ogni UUID di test effettivamente archiviato;
`archive` è relativo al manifest):

```json
{
  "schema": 1,
  "package": "it.ecamuto.yourec.android.prototype",
  "serial": "SERIAL",
  "files": [{
    "path": "files/capture-prototype/completed/12345678-1234-1234-1234-123456789abc.mp4",
    "sha256": "SHA256_DEL_FILE",
    "archive": "media/12345678-1234-1234-1234-123456789abc.mp4"
  }]
}
```

```sh
# Simulazione; la directory di audit deve essere nuova.
python3 tools/cleanup_captures.py --serial SERIAL --manifest MANIFEST.json --output AUDIT_DRY_RUN
# Applicazione solo dopo la verifica del piano di pulizia.
python3 tools/cleanup_captures.py --serial SERIAL --manifest MANIFEST.json --output AUDIT_BACKUP --apply
python3 -m unittest discover -s tools -p 'test_*.py'
```

Non ammette directory Projects/RecentlyDeleted, glob o percorsi esterni all'archivio
del prototipo. Un errore lascia backup e audit da ispezionare; non avvia l'app e
non esegue cancellazioni alternative.

Licenza e provenienza: [LICENSE](../LICENSE) e [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
