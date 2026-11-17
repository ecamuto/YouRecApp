# Android 0.3: cambio camera durante il take

17 novembre 2026. Base: `48ac0f6`, branch `android/bootstrap`.

**Stato: implementato, installato in place e verificato nelle prove brevi sul
Samsung SM-A256B/Android 16.** Il cambio mantiene lo stesso take e la traccia audio;
il video conserva l'ultima immagine per circa un secondo. Verificati verticale e
orizzontale, entrambe le camere iniziali, pausa/ripresa e lifecycle durante cambio.
Restano necessari secondo OEM, misure di sincronismo e prove lunghe prima di un rilascio.

## Comportamento implementato

Il pulsante Frontale/Posteriore è disponibile da fermo, durante la registrazione
e in pausa. Durante il cambio i controlli di nuovo cambio e pausa/ripresa sono
disabilitati; Ferma resta disponibile. Un timeout di 5 s interrompe il take con
errore e conserva il file provvisorio.

Il microfono AudioRecord, l'encoder AAC, l'encoder AVC e il muxer restano gli stessi.
Il cambio chiude la camera precedente, ne attende `onClosed` e apre quella nuova.
Non chiama pausa/ripresa e non sottrae il tempo di riconfigurazione dalla timeline.
Se il cambio avviene durante una pausa esplicita, il take rimane in pausa: non
riprende né include automaticamente il PCM escluso.

Fra camera e encoder è stato aggiunto `VideoCompositor`, su un thread EGL/GLES
separato dal thread audio/muxer. Una SurfaceTexture riceve le immagini della camera;
un framebuffer conserva l'ultimo fotogramma normalizzato. Una cadenza di circa
30 fps alimenta il canvas persistente 1920×1080 anche mentre la camera è chiusa.
Il video mantiene l'ultima immagine durante il cambio; l'audio continua per lo
stesso tempo reale. Questo fermo immagine è dichiarato nella UI. La preview Camera2
rimane un'uscita separata e può mostrare il transitorio di riapertura.

Il clock del rendering viene ancorato una volta al timestamp hardware del primo
fotogramma e avanza con BOOTTIME. Non viene riancorato al cambio camera. Il gate
pausa/ripresa condiviso di `CaptureClock` resta a valle dell'encoder, con taglio
PCM prima dell'AAC. Blocchi nel rendering non devono bloccare il thread PCM.

Il compositore rimuove l'orientamento implicito della SurfaceTexture, mantenendone
il crop effettivo, e normalizza l'immagine rispetto alla rotazione MP4 fissata
all'inizio del take. Il file resta non specchiato; solo la preview selfie usa il
mirror. Con sensori montati ad angoli che scambiano gli assi, il fit può introdurre
bande anziché deformare l'immagine; questa combinazione richiede collaudo dedicato.
I timestamp EGL vengono impostati esplicitamente. Riferimenti:
[SurfaceTexture](https://developer.android.com/reference/android/graphics/SurfaceTexture),
[trasformazioni CameraX](https://android.googlesource.com/platform/frameworks/support/+/6353c5d0cbdb0a766f9640dde3ffcb0f9e4cfcf1/camera/camera-core/src/main/java/androidx/camera/core/processing/SurfaceOutputImpl.java),
[presentation time EGL](https://developer.android.com/reference/android/opengl/EGLExt#eglPresentationTimeANDROID(android.opengl.EGLDisplay,android.opengl.EGLSurface,long)).

Allo stop si attende il drenaggio Camera2, si chiude il produttore EGL e solo dopo
si segnala EOS all'encoder. Il percorso di errore prima dell'avvio del microfono
rilascia AudioRecord e rende possibile l'EOS audio. Nessun file viene cancellato. La riapertura della preview non sovrascrive più
il messaggio di errore o di salvataggio del take precedente.

Per l'uscita destinata al compositore è impostato esplicitamente
`TIMESTAMP_BASE_SENSOR`: con sensore REALTIME i campioni condividono BOOTTIME con
AudioRecord. Il default per una superficie MediaCodec può invece usare MONOTONIC,
un clock che diverge da BOOTTIME durante lo standby. La precedente build 0.2,
riprovata dopo il lungo standby, è tornata idle prima del primo frame valido,
lasciando un MP4 provvisorio di 2410 byte. Non ne è stato salvato il messaggio
d'errore, sovrascritto dalla preview: il collegamento al dominio temporale resta
un'ipotesi coerente con la documentazione, non una causa provata da quel file.
La nuova pipeline con base esplicita registra correttamente nella stessa sessione.
[Timestamp delle uscite Camera2](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#TIMESTAMP_BASE_DEFAULT).

## Verifiche completate

`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passa:
9 test JVM, 0 errori lint e 6 avvisi di aggiornamento versioni. Questi test coprono
clock/persistenza già presenti; non eseguono shader, Camera2 o il cambio fisico.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, versione
`0.3.0-prototype`/versionCode 3, installato in place sul Samsung. SHA-256:
`57c3f4f25f61c18e7c135a56c0d9f6f9b5f24960ea31a6071c6e3b4135e1f40f`.
Le modifiche preesistenti al rapporto 0.2 sono state conservate.

## Copertura delle prove fisiche

1. Snapshot inventario, backup verificato dei media e preferenze; riproduzione
   dell'assenza del controllo live nella build precedente. Installazione in place.
2. Una ripresa per orientamento, posteriore → frontale → posteriore senza stop.
   Confronto scene della preview e del file: bordi, testo, verso verticale, mirror.
   Ripetere partendo dalla frontale per verificare entrambe le rotazioni MP4 iniziali.
3. Tono continuo attraverso i cambi, PTS AAC consecutivi e verifica del contenuto
   PCM decodificato. PTS regolari da soli non dimostrano che il microfono non sia
   stato silenziato. Misurare il fermo immagine e confrontarlo con i log del cambio.
4. Cambio in pausa, resume, stop durante cambio, background durante cambio,
   riapertura, nuova ripresa e lettore. Nessuna pubblicazione di file invalidi.
5. Decodifica integrale e PTS crescenti del file reale, codec/colore/dimensioni
   effettivi; snapshot sorgenti/APK e checksum dei media preesistenti invariati.

## Misure e anomalia conservata

| Prova | File | Passo video massimo | Passo AAC massimo | Tono continuo sui due canali |
| --- | --- | ---: | ---: | --- |
| Orizzontale, partenza posteriore | `397ef622-db63-42a5-aa06-d7e13434ead7.mp4` | 43,288 ms | 21,334 ms | Calo sul canale 0; vedi sotto |
| Verticale, partenza posteriore | `73daf23a-9414-4643-b994-880c838ddaf4.mp4` | 40,745 ms | 21,334 ms | Passa |
| Verticale, partenza frontale | `c63bb700-8b02-477f-a27c-f09e7de9e6d0.mp4` | 52,167 ms | 21,334 ms | Passa |
| Orizzontale ripetuto, partenza frontale | `a09d2235-807f-445e-9921-44f1db4c8b02.mp4` | 40,000 ms | 21,334 ms | Passa |

I quattro file hanno PTS strettamente crescenti e decodifica integrale senza errori,
H.264 1920×1080 BT.709 limited e AAC stereo 48 kHz. La rotazione MP4 è 0°, 90°
o 270° secondo la camera/orientamento iniziali; resta fissa durante il take.
Il confronto screenshot/fotogramma mostra campo completo, immagini dritte e
mirror solo nell'anteprima selfie, compreso il cambio con correzione di 180°
quando i due sensori hanno orientamenti opposti in portrait.

I log dei cambi completati misurano circa 0,96–1,22 s dal comando al primo frame
della nuova camera. Il video codificato continua con l'ultima immagine per quel
periodo; il tempo non viene eliminato. Non è una misura di latenza A/V assoluta.

Il tono 700 Hz è stato riprodotto fisicamente per tutta la prova. Il nuovo controllo
`--continuous-tone-hz 700` esamina separatamente i canali, in finestre da 20 ms ogni
10 ms, escludendo 200 ms alle estremità. Richiede ampiezza del tono oltre −55 dBFS
in ogni finestra. Un WAV di controllo con 200 ms di silenzio produce 19 finestre
fallite: il controllo rileva buchi di contenuto anche con PTS regolari.

Nella prima prova orizzontale il canale 0 scende sotto soglia fra circa 15,12 e
16,64 s; il canale 1 rimane continuo. Il secondo cambio viene richiesto dopo
quell'intervallo. I timestamp non presentano buchi e non c'è stata una segnalazione
di microfono silenziato, ma questo non dimostra l'origine del calo. Non attribuito
né corretto arbitrariamente come difetto del cambio; l'artefatto è conservato.
Le due prove verticali superano il controllo sui due canali, con minimi del tono
fra −33,78 e −30,00 dBFS nella prima e circa −32 dBFS nella seconda.
La ripetizione orizzontale con telefono appoggiato supera il controllo sui due
canali: minimi −46,10 / −29,64 dBFS, nessuna finestra sotto soglia. L'anomalia del
primo file rimane documentata: queste ripetizioni non ne dimostrano la causa e
non garantiscono un livello microfonico costante in ogni posizione o percorso OEM.

Le tre prove `lifecycle/` verificano Ferma durante cambio, Home durante cambio e
doppio tocco rapido. Tutte pubblicano file decodificabili, con passo AAC massimo
21,334 ms e passo video massimo di 41,700, 37,856 e 36,400 ms. `timing.json`
riporta i valori per UUID, ma non indica a quale delle tre prove corrisponde ogni file. Dopo riapertura del processo sono presenti
27 riprese completate; con l’ultima ripetizione sono 28. Il ritorno dalla libreria/lettore è stato verificato nelle
prove principali. Le 21 riprese preesistenti hanno checksum invariati; preferenze
di rotazione globali invariate. Il file provvisorio della prova 0.2 è conservato.

Gli script e le evidenze locali sono separati in `.benchmarks/android/live-switch/`:

- `candidate1/`: snapshot sorgenti e hash dell'APK usato in tutte le prove fisiche.
- `landscape/`, `portrait/`, `portrait-front/`, `landscape-front/`: screenshot/XML, fotogrammi estratti e file dei quattro take principali. Nei due test con partenza frontale alcuni nomi degli screenshot riportano ancora la vecchia etichetta della prima camera: fanno fede pulsanti visibili, log e parametro `front` dello script.
- `continuity.json` nelle cartelle dei take: controlli PTS/decodifica e tono su finestre brevi, incluso il fallimento del primo landscape.
- `lifecycle/`: tre take e sequenze di stop/Home/doppio tocco/riapertura, con `timing.json`.
- `dropout-negative-control.json`: controllo negativo del rilevatore su silenzio inserito.
- `sha256-before.txt`, `sha256-originals-after.txt`, `settings-before.json`, `settings-after.json`: verifica di conservazione.
- `final/`: snapshot e hash finali, comprendenti il controllo Python esteso e i report di build/test/lint. Il codice Android e l'APK coincidono con `candidate1/`; i successivi aggiornamenti riguardano documentazione e strumento QA.

Comando riproducibile dalla radice, per le prove con tono continuo:

```sh
python3 android/tools/check_capture.py FILE.mp4 --decode \
  --max-video-step-ms 60 --max-audio-step-ms 23 \
  --continuous-tone-hz 700 --output report.json
```

Le soglie riguardano questi take a circa 30 fps/AAC 48 kHz. Il controllo del tono
va applicato solo a una sorgente di prova mantenuta costante per tutto il file.

Il collegamento inizialmente `unauthorized` è tornato disponibile dopo revoca
manuale delle autorizzazioni sul telefono e riavvio del server con chiave esplicita;
non sono state generate o sostituite chiavi del Mac. Inventario, backup verificati
e preferenze delle 21 riprese preesistenti sono in questa directory. Il file
provvisorio della prova 0.2 è conservato anche localmente in `baseline-pending/`.
Nessuna pulizia del telefono è stata eseguita durante queste prove. Nella successiva
sessione di [interruzioni e riapertura](ANDROID-INTERRUPTIONS.md), l'utente ha richiesto
la rimozione delle copie dei test dal Samsung: pulizia completata dopo backup
integrale verificato, con tutti questi media ed evidenze conservati sul Mac.

## Limiti

Non promette un taglio video istantaneo: con una sola camera aperta alla volta
la riconfigurazione richiede tempo e viene rappresentata con il fermo immagine.
Il nuovo percorso RGB/GPU richiede prove di qualità, colore, consumo e termica.
La cadenza è richiesta e dipende dal carico; non è una garanzia di 30 fps esatti.

Il riferimento temporale GPU preserva il tempo trascorso, ma non corregge
automaticamente differenze di latenza delle camere: lip-sync e drift restano da
misurare. Take da 30–60 minuti, secondo OEM, interruzioni/routing e recovery sono
ancora aperti. Non sono stati aggiunti doppia camera, effetti o editor.
