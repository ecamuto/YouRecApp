# Android capture prototype

Implementazione della fase 0 del [piano Android](../docs/ANDROID-PLAN.md), nel fork
`ecamuto/YouRecApp`. Versione **0.2.0-prototype**, launcher **Camera Lab**,
application ID `it.ecamuto.yourec.android.prototype`. Il progetto iOS resta nella struttura originale.

Il modulo Kotlin/Compose usa **Camera2, MediaCodec e AudioRecord indipendente**:

- Android 13/API 33 minimo; compilazione e target SDK 36.
- Camera singola, frontale/posteriore selezionabile da fermo, H.264 1920×1080 SDR e AAC stereo 48 kHz.
- 30 fps richiesti; il frame rate effettivo dipende dal dispositivo e dalla scena.
- Registra, pausa, riprendi, ferma; timeline comune per rimuovere dalle due tracce lo stesso intervallo di pausa.
- Al resume si attende un keyframe prima di ammettere nuovamente video e PCM; l'audio in pausa viene escluso prima della codifica AAC.
- Orientamento bloccato durante il take; preview 16:9 adattata allo spazio disponibile, selfie specchiato soltanto in anteprima.
- Stop entrando in background; schermo acceso durante la ripresa; finalizzazione asincrona.
- Libreria persistente e lettore locale; file pubblicati dopo controllo delle tracce video/audio e rename atomico.
- Riprese incomplete conservate separatamente, senza pulizia automatica.

È un prototipo di acquisizione verificato su un Samsung, non una versione pubblicabile o il completamento della fase 0.
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

Non sono implementati cambio camera durante il take, append, doppia camera, HDR/4K, effetti, editor,
condivisione, import progetti iOS, modelli ML e recovery dei file incompleti.

## Verifica

Build debug, **9 test JVM** e lint completati: 0 errori, 6 avvisi sulle versioni della toolchain/dipendenze.
I test coprono il clock comune, tagli PCM ai confini delle pause e conservazione/pubblicazione dei file.
Le prove reali coprono le due camere, verticale/orizzontale, pause multiple, stop da pausa,
background, nuove riprese e lettore. Take lunghi con misura di drift, interruzioni audio e secondo OEM restano aperti.

Controllo riproducibile di un file locale con Python 3, FFmpeg e FFprobe:

```sh
python3 tools/check_capture.py ripresa.mp4 --decode --max-video-step-ms 60 --max-audio-step-ms 23 --output report.json
```

Le soglie sono quelle delle prove brevi a 30 fps/AAC 48 kHz, non un criterio universale per ogni formato.
Il controllo rileva gap/PTS e problemi di decodifica, non il lip-sync o la qualità percepita.

Licenza e provenienza: [LICENSE](../LICENSE) e [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
