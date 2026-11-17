# Motore Android 0.2: acquisizione con clock comune

17 novembre 2026, branch `android/bootstrap`. Questo passo implementa il prototipo
acquisizione della fase 0. Non completa la fase né il porting dell'editor.

## Passo successivo: prototipo 0.3

Il cambio camera durante il take è implementato e collaudato nelle prove brevi
sul Samsung. Il percorso 0.3 usa un compositore GPU aggiuntivo: le misure seguenti
restano riferite al backend 0.2. Il rapporto 0.3 documenta separatamente nuove prove,
fermo immagine durante il cambio e limiti delle misure audio.
Dettagli: [ANDROID-SWITCH.md](ANDROID-SWITCH.md).

## Problema e comportamento implementato

Nel backend CameraX 0.1 la pausa/ripresa lasciava un intervallo massimo di
**231,689 ms fra PTS video e 133,042 ms fra PTS audio** nel file di regressione.
I controlli funzionavano, ma non era una base sufficiente per il motore previsto dal piano.

Il backend attuale usa Camera2 e due encoder MediaCodec, con AudioRecord separato
dalla camera. Una sola timeline ammette gli intervalli attivi di entrambe le sorgenti.
Il resume richiede un keyframe: all'uscita dalla pausa l'encoder riceve una richiesta
di sync frame e la UI resta nello stato `RESUMING` ("Preparazione ripresa…") finché
arriva un fotogramma video decodificabile; lo stesso istante apre il gate PCM.
Questo evita di comprimere indipendentemente i buchi delle due tracce.

| Componente | Responsabilità |
| --- | --- |
| `CaptureController` | Camera2, superfici, lifecycle, configurazione preview e orientamento, finalizzazione |
| `CapturePreview` | SurfaceView con buffer 1920×1080; misura e layout 16:9 nello spazio disponibile |
| `AvRecorder` | Thread microfono, codec AVC/AAC, muxer, code limitate, EOS ed errori |
| `CaptureClock` | Intervalli attivi sul clock BOOTTIME, mappatura video e taglio PCM |
| `CaptureStore` | File provvisori separati, pubblicazione atomica e rilettura |
| `tools/check_capture.py` | Controllo locale riproducibile di PTS, decodifica e toni di prova |

AudioRecord acquisisce PCM16 stereo 48 kHz. Il riferimento viene da
`getTimestamp(TIMEBASE_BOOTTIME)`, associando posizione dei frame e tempo; il tempo
di completamento di una lettura non viene trattato come tempo del primo campione.
Camera2 deve esporre timestamp REALTIME confrontabili. Questi requisiti vengono
controllati; la compatibilità non è dedotta dal solo numero di versione Android.
Riferimenti: [AudioTimestamp](https://developer.android.com/reference/android/media/AudioTimestamp),
[AudioRecord](https://developer.android.com/reference/android/media/AudioRecord),
[timestamp camera](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE).

In pausa camera, microfono e codec restano attivi. Il video viene escluso prima
del muxer e il PCM viene escluso **prima dell'AAC**; i campioni sospesi non finiscono
nel file. Il consumo durante la pausa resta da ottimizzare. Code e attese sono
limitate: al massimo 200 blocchi PCM in attesa del keyframe, 100 ingressi verso
l'encoder audio e 5 s per la chiusura. Una discontinuità PCM oltre 3 ms fra PTS
atteso e timestamp del campione, una coda piena o il timeout interrompono la
ripresa con errore anziché nascondere la perdita. Allo stop si attende il drenaggio
della sequenza camera prima dell'EOS video, e si finalizza dopo l'EOS di entrambe le tracce.

Il file è AVC 1920×1080 SDR, senza B-frame, con rotazione MP4 per portrait;
AAC LC stereo 48 kHz. Richiesti 30 fps e 17 Mb/s video, 256 kb/s audio.
L'intervallo richiesto fra keyframe è 1 s; al resume il sync frame viene chiesto
esplicitamente, senza attendere il keyframe periodico.
Il contenuto selfie resta non specchiato nel file. La preview applica il mirror
attraverso l'output Camera2, perché una scala della View non ha prodotto il mirror
atteso della superficie separata sul Samsung. La misura della SurfaceView avviene
nello stesso passaggio di layout dei controlli: l'aggiunta del timer non deve
lasciare una superficie delle vecchie dimensioni sovrapposta a titolo e pulsanti.
Riferimenti: [preview Camera2](https://developer.android.com/media/camera/camera2/camera-preview),
[mirror dell'output](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setMirrorMode(int)).

## Misure sul Samsung

Dispositivo fisico: Samsung SM-A256B, Android 16/API 36. Installazioni in place;
controlli pubblici usati attraverso la UI. File copiati localmente e decodificati
integralmente. Le misure seguenti riguardano take brevi, non un benchmark di drift.

| Backend e prova | File | Massimo passo video | Massimo passo AAC |
| --- | --- | ---: | ---: |
| CameraX 0.1, pausa/ripresa | `ccc06f45-0733-41c4-98be-2a7edc113960.mp4` | 231,689 ms | 133,042 ms |
| Clock comune 0.2, selfie landscape, 3 pause | `1525aedd-e9f7-42fe-92ca-386eb16ae071.mp4` | 33,411 ms | 21,334 ms |
| Clock comune 0.2, posteriore portrait, 1 pausa | `31c30434-bafa-446a-ac54-259446eb1ab8.mp4` | 33,378 ms | 21,334 ms |
| Clock comune 0.2, selfie portrait, stop da Home | `56000fcb-1d39-4c7e-9bf7-3e9c967c1516.mp4` | 33,366 ms | 21,334 ms |

PTS strettamente crescenti e decodifica senza errori nei tre file 0.2.
21,333… ms è la durata normale di un pacchetto AAC da 1024 campioni a 48 kHz:
non è un buco residuo di quella durata. Il controllo con soglie video 60 ms/audio
23 ms passa sui nuovi file e fallisce sul vecchio file, rilevando entrambe le discontinuità.

La verifica dei contenuti usa tre toni riprodotti fisicamente: 700 Hz durante
registrazione, 1700 Hz in pausa, 1100 Hz dopo resume. Nel file sono rilevati i toni
attivi sui due canali e non è rilevato il tono sospeso come sequenza isolata sostenuta.
Il rilevatore trova correttamente il tono 1700 Hz nel WAV di controllo positivo.
Cerca finestre da 200 ms ogni 100 ms, ampiezza oltre −65 dBFS, isolamento oltre
12 dB rispetto a ±20/40 Hz e almeno cinque finestre consecutive, su ogni canale
separato. Non afferma assenza di qualunque energia a 1700 Hz: voce e rumore possono
produrre picchi isolati. Questa prova non misura il sincronismo labiale.

Dopo gli interventi sulla finalizzazione sono state ripetute le sequenze
Registra → Pausa → Ferma, nuova ripresa con resume, libreria → Riproduci → ritorno.
Gli aggiornamenti della UI su main thread mantengono l'ordine stop/salvataggio/idle;
i callback di aperture camera superate vengono ignorati.
Dopo la correzione finale della preview il confronto su entrambe le camere,
in verticale e orizzontale, mostra la scena completa senza deformazione o
sovrapposizione ai controlli. Nel selfie porta e scritta sulla sedia cambiano lato
fra preview e file come previsto dal mirror della sola anteprima. La scena
posteriore nitida consente ora anche il confronto portrait rimasto aperto nello storico.

Quattro file della build finale passano PTS e decodifica integrale:

| Prova finale | File | Massimo passo video | Massimo passo AAC |
| --- | --- | ---: | ---: |
| Posteriore portrait, stop da pausa | `368bcb9d-c294-45c0-846b-174907fd766a.mp4` | 33,355 ms | 21,334 ms |
| Selfie portrait, pausa/ripresa | `64dd9436-bbc0-4170-92f9-4caca3a2a359.mp4` | 33,377 ms | 21,334 ms |
| Posteriore landscape, stop da pausa | `cc2d51b4-d3c6-4a9c-b17a-6a92caccb67c.mp4` | 33,345 ms | 21,334 ms |
| Selfie landscape, pausa/ripresa | `d7d808d9-d0bf-4515-beb6-ca74f17fea93.mp4` | 33,356 ms | 21,334 ms |

In entrambe le sessioni finali: nuova registrazione dopo stop, apertura del lettore
dal pulsante pubblico e ritorno alla camera inattiva. Al termine di quella sessione
le 21 riprese completate erano sul telefono, comprese tutte le 9 preesistenti con
SHA-256 invariato. Nessun file cancellato; preferenze di rotazione globali invariate
rispetto allo snapshot. In seguito, su richiesta dell'utente, i file di test sono
stati rimossi dal telefono dopo un'archiviazione locale verificata:
[interruzioni e pulizia](ANDROID-INTERRUPTIONS.md).

## Riproducibilità ed evidenze

Directory locali escluse da Git, contenenti anche immagini e audio privati.
Tutti i percorsi sono relativi alla radice del repository:

- `.benchmarks/android/20261117T064941Z/`: backend CameraX storico e backup iniziali.
- `.benchmarks/android/pause-resume/`: inventario e checksum delle 9 riprese preesistenti, preferenze iniziali, prima prova nativa, sorgenti di confronto.
- `.benchmarks/android/pause-resume/final/`: tre prove della tabella, screenshot/XML, log, `all-timing-check.json`, `tone-check.json`, controllo positivo e negativo, snapshot sorgente/hash.
- `.benchmarks/android/pause-resume/final/release-check/` e `.../final/acceptance/`: prove successive di stop, riavvio e lettore; snapshot con hash per distinguere le build intermedie.
- `.benchmarks/android/pause-resume/final/preview-fix/` e `.../final/landscape-final/`: prove fisiche verticali/orizzontali dopo l'ultima correzione, screenshot e fotogrammi confrontati, file e `timing.json`; snapshot/hash in `preview-fix/`.
- `.benchmarks/android/pause-resume/final/sha256-originals-after.txt` e `.../final/settings-after.json`: verifica conservazione dati e preferenze.

La build delle tre misure principali ha SHA-256 APK
`ece2d3c28d2318911e59a428d720864d9cc05d491bf8850d1e2cc878c109d730`.
La build finale con correzione preview ha SHA-256
`e19f430cc390d932f2b6a9cd515529b5a9f4651351a2d7035532cb4baabbebe9`.
Gli snapshot distinguono le modifiche di lifecycle/layout successive; il clock e
la selezione dei campioni audio/video della tabella sono gli stessi.

Esempio, dalla radice del repository:

```sh
python3 android/tools/check_capture.py FILE.mp4 --decode \
  --max-video-step-ms 60 --max-audio-step-ms 23 --output report.json
```

Per la prova con toni aggiungere `--require-tone-hz 700 --require-tone-hz 1100 --reject-tone-hz 1700`.
Il decoder conserva il timebase sorgente con `-fps_mode:v passthrough -enc_time_base:v demux`:
un muxer di verifica a 1/30 può arrotondare timestamp VFR distinti allo stesso DTS.

Build completata con JDK 21/SDK 36:
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
**9 test JVM superati**, 0 errori lint, 6 avvisi di aggiornamento versioni.
Cinque test verificano clock/tagli PCM e quattro persistenza/pubblicazione dei file.
Non è stata modificata la pipeline iOS. I sorgenti Kotlin, Gradle e XML dello snapshot
della build finale (`.benchmarks/android/pause-resume/final/preview-fix/source.tar.gz`) coincidono con il commit
`bf05e54` del branch `android/bootstrap`.

## Limiti e prossima verifica

Non dichiarare acquisizione pronta al rilascio finché non passano anche:

- Take da 30–60 minuti con riferimento A/V a inizio/fine, misure drift, memoria, consumo e termica. Il precedente take da 11:27 era CameraX e non valida il nuovo motore.
- Secondo produttore e combinazioni codec/camera diverse; la configurazione 1080p REALTIME/stereo è attualmente obbligatoria.
- Chiamate, perdita camera, silenziamento, permessi revocati, routing e microfoni esterni, poco spazio e stress del ciclo di vita.
- Recupero dopo terminazione forzata: il MP4 non è frammentato; conservare `pending` non ne garantisce il recupero. Lo stop a inizio configurazione è gestito, ma richiede ulteriori prove di stress.
- Misura di sincronismo assoluto, priming AAC, click ai tagli e ascolto su materiale rappresentativo. PTS regolari non equivalgono a lip-sync provato.

Al termine del collaudo 0.2 il cambio camera durante il take era ancora assente.
Il prototipo 0.3 lo implementa mantenendo la sorgente audio, con misure e verifiche
fisiche separate nel rapporto dedicato. Rendering/export,
modello progetto, editor, ML e compatibilità iOS rimangono i successivi blocchi del
[piano](ANDROID-PLAN.md).
