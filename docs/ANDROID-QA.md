# Android: verifica del prototipo di acquisizione

## Stato attuale: motore 0.2

Il backend CameraX descritto sotto è stato sostituito da Camera2/MediaCodec con
AudioRecord indipendente e clock comune. **Build, 9 test JVM e lint passano**
(0 errori, 6 avvisi di versione). Le prove brevi sul Samsung non mostrano più i
buchi al resume rilevati nella versione iniziale. Non dimostrano ancora il drift
su take lunghi o la compatibilità con altri produttori.

Implementazione, confronto prima/dopo, evidenze e limiti aggiornati:
[ANDROID-CAPTURE.md](ANDROID-CAPTURE.md).

## Storico: CameraX 0.1

Le sezioni seguenti documentano esclusivamente il backend precedente: i suoi
risultati, hash e problemi non vanno attribuiti al motore 0.2.

### Stato storico

Implementazione iniziale su `android/bootstrap`, 17 novembre 2026.
È solo il primo percorso di acquisizione della fase 0; non equivale al completamento della fase.

| Controllo | Risultato |
| --- | --- |
| Build APK debug | Riuscita con JDK 21, Gradle 8.13, AGP 8.13.1 e SDK 36 |
| Test JVM dell'archivio | 4 superati, 0 errori/fallimenti |
| Android Lint | 0 errori; 10 avvisi su versioni dipendenze, Gradle e target SDK più recenti |
| Dispositivo fisico | Samsung SM-A256B, Android 16/API 36; prototipo installato e avviato |
| Inquadratura e mirror su camera reale | Landscape confrontato sulle due camere; portrait confrontato sul selfie. Camera posteriore portrait molto sfocata: verifica geometrica precisa ancora aperta |
| Orientamento | Portrait registrato sulle due camere; rotazione manuale frontale di 90° verificata nel take da 11 min 27 s, senza interruzione; UI bloccata durante il take e sbloccata dopo stop |
| Codec e decodifica del file reale | Due clip della build finale: H.264 1920×1080 SDR BT.709, AAC stereo 48 kHz, decodifica senza errori |
| Pausa/ripresa | Controlli funzionanti; rilevate discontinuità PTS al resume, quindi continuità A/V non ancora accettata |
| Background, riapertura e lettore | Stop in background con file salvato; 5 riprese presenti dopo chiusura/riapertura; lettore verificato dal controllo pubblico |
| Take lungo, drift, chiamate e secondo produttore | Ancora da eseguire |

Sono disponibili evidenze locali delle build iniziali e della build finale, con i limiti indicati sotto.
Le regole di evidenza di [CAMERA-QA.md](CAMERA-QA.md) si applicano anche a questo modulo,
con telefoni Android al posto degli iPhone indicati per lo sviluppo iOS.
Non usare una build riuscita o un emulatore come accettazione della camera.

Comando completato sul codice finale: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
APK prodotto: `android/app/build/outputs/apk/debug/app-debug.apk`.
Report locali generati: `android/app/build/reports/tests/testDebugUnitTest/index.html` e
`android/app/build/reports/lint-results-debug.html`; gli artefatti di build sono esclusi da Git.
Le versioni restano fissate alla baseline scelta. Il primo build ha anche rilevato un NDK locale
incompleto: nessun sorgente NDK è compilato in questo passo e le librerie delle dipendenze
sono state incluse senza stripping. Questo non verifica ancora la toolchain necessaria al DSP C/C++.

## Prima sessione sul Samsung, 17 novembre 2026

Installazione iniziale assente: installato il prototipo con `adb install -r`, avvio riuscito.
I permessi sono stati concessi sul telefono e la preview della camera frontale è stata osservata.
Il percorso di diniego/ripristino dei permessi non è ancora stato collaudato.

Due file completati sono stati copiati e decodificati integralmente con FFmpeg senza errori.
Durate video: circa 5,866 e 3,883 secondi; frame rate medio circa 30,00 e 29,61 fps.
Entrambi hanno video H.264 1920×1080 con matrice di rotazione portrait, SDR BT.709,
e audio AAC stereo 48 kHz. Queste letture non dimostrano qualità audio, mirror corretto,
campo visivo o sincronizzazione: nella prova automatica la camera posteriore era coperta
e non consentiva una verifica geometrica utile.

La prova automatica di pausa/ripresa si è fermata prima del comando Pausa: la registrazione
era già tornata inattiva. I log mostrano una rotazione portrait→landscape alle 07:57:09,
ricreazione dell'attività e distacco della camera durante il take.
La revisione ha identificato un errore nel `DisposableEffect` della finestra:
il cleanup dell'effetto idle leggeva lo stato delegato già aggiornato a busy e rimuoveva
il blocco dell'orientamento impostato dal pulsante Registra.

Correzione: l'effetto cattura il valore Boolean della propria istanza e mantiene il blocco
durante la ripresa/finalizzazione. Build, 4 test JVM e lint sono nuovamente passati;
aggiornamento in place riuscito. I checksum delle due riprese preesistenti coincidono
prima e dopo l'aggiornamento. Nessun file sul telefono è stato cancellato.

APK iniziale SHA-256: `d28fcf02529563ab0dcfa1d025829ae7c3b263baf342b677e5a0a66b827d91f1`.
APK con correzione SHA-256: `5275235fa7e93fc7a20bb12032311a317a904838953c483219247cee7921e5a6`.
Snapshot dei sorgenti, inventari, backup, screenshot, log, clip e metadati sono conservati
localmente in `.benchmarks/android/20261117T064941Z/`, escluso da Git.
Il codice corretto è archiviato nella sottocartella `orientation-fix/`.

La prima ripetizione era rimasta pendente perché il telefono era nella schermata di blocco
(`showing=true`). Dopo lo sblocco da parte dell'utente il collaudo è ripreso, come descritto sotto.

## Ripetizione sul dispositivo sbloccato

La build con correzione dell'orientamento ha completato una ripresa posteriore con pausa/ripresa
e stop attraverso i pulsanti dell'app. Il take è proseguito durante una richiesta controllata
di cambio rotazione via Window Manager. Le impostazioni iniziali erano rotazione libera,
`accelerometer_rotation=1`, `user_rotation=0` e sono state ripristinate dopo ciascuna prova.
Questo test non sostituisce la rotazione manuale del telefono.

Il confronto preview/file ha poi rilevato un secondo difetto: la Preview CameraX predefinita
mostrava un campo 4:3, mentre il VideoCapture registrava 16:9. Correzione in `CaptureController`:
preferenza 16:9 della Preview e ViewPort comune ai due use case, orientato per l'output.
La soluzione segue la [configurazione ufficiale del crop CameraX](https://developer.android.com/media/camera/camerax/configuration#crop-rect).

Nuova build, 4 test JVM e lint superati; installazione in place, senza cancellazioni.
APK finale SHA-256: `1c256759efed2a6ed711e2697d850545332d74e278c416ce6463e3719f142773`.
Sorgenti esatti, checksum, screenshot, gerarchie UI, filmati e metadati sono in
`.benchmarks/android/20261117T064941Z/framing-fix/`, escluso da Git.

Sul codice finale sono state ripetute attraverso la UI:

- Camera posteriore: Registra → richiesta di cambio rotazione → Pausa → Riprendi → Ferma.
  Il file è stato pubblicato e il take non si è interrotto durante la richiesta di rotazione.
- Cambio a camera frontale da fermo, Registra, tasto Home, ritorno all'app.
  La ripresa è stata fermata e pubblicata senza file residui in pending.
- Chiusura del processo da fermo e riapertura: tutte e 5 le riprese presenti.
  Apertura del lettore tramite la prima scheda della libreria, riproduzione e ritorno alla libreria riusciti.
- Confronto delle screenshot reali con fotogrammi estratti: campo 16:9 coerente in landscape
  per camera posteriore e frontale; inversione orizzontale della sola preview frontale osservata.
  Non è una verifica geometrica completa con mire o su altri orientamenti/dispositivi.
- Le due riprese preesistenti hanno gli stessi SHA-256 del backup precedente.
  Nessuna ripresa diagnostica o dell'utente è stata cancellata dal telefono.

### Misure della build finale

| Clip | Video posteriore con pausa | Video frontale, stop in background |
| --- | --- | --- |
| Durata video | 19,598 s | 9,633 s |
| Formato | H.264 High, 1920×1080, 8 bit, BT.709 | H.264 High, 1920×1080, 8 bit, BT.709 |
| Audio | AAC stereo, 48 kHz | AAC stereo, 48 kHz |
| Frame rate nominale/medio | 30 / circa 29,697 fps | 30 / circa 30,001 fps |
| Decodifica completa FFmpeg | 0 errori | 0 errori |
| PTS video/audio strettamente crescenti | Sì | Sì |
| Massimo intervallo tra PTS video | 231,689 ms al resume | 33,356 ms |
| Massimo intervallo tra PTS audio | 133,042 ms | 22,146 ms |

Il gap video del take con pausa è tra 12,599611 e 12,831300 s. Il controllo Pausa funziona,
ma il resume non è ancora continuo: non dichiarare questa pipeline priva di buchi audio/video.
È un problema da misurare nuovamente nel prototipo di audio indipendente; non basta la
decodifica senza errori per accettare il montaggio temporale. Anche il file frontale ha una
coda audio leggermente più lunga del video, da includere nelle verifiche ai confini.

Le tracce audio contengono segnale ambientale debole (RMS complessivo circa −65 dBFS);
non è stata eseguita una prova vocale con riferimento visivo/sonoro, quindi qualità del parlato,
sincronizzazione percettiva e drift restano non verificati.

Restano aperti: geometria precisa del portrait posteriore, ulteriori rotazioni fisiche sulla camera posteriore,
stop immediato dopo Start, chiamate/routing audio, recovery/spazio insufficiente,
take di 30–60 minuti con misure termiche e secondo produttore. La fase 0 resta incompleta.

## Collaudo verticale sullo stesso APK

Dopo che l'utente ha posizionato fisicamente il Samsung in verticale, sono state registrate
altre due clip dai controlli Registra/Ferma, senza cambiare le impostazioni del telefono.
Le screenshot misurano 1080×2340 e mostrano l'app in portrait durante entrambe le riprese.
L'APK e i sorgenti Kotlin/XML hanno gli stessi checksum della build finale indicata sopra;
non sono state necessarie ulteriori modifiche al codice applicativo.

| Misura | Posteriore portrait | Frontale portrait |
| --- | --- | --- |
| Durata video | 9,133 s | 9,033 s |
| Buffer codificato | H.264, 1920×1080, BT.709 | H.264, 1920×1080, BT.709 |
| Matrice display letta da FFprobe | −90° | +90° |
| Fotogramma con rotazione applicata | 1080×1920 | 1080×1920 |
| Audio | AAC stereo 48 kHz | AAC stereo 48 kHz |
| Decodifica completa | 0 errori | 0 errori |

I segni della matrice sono quelli riportati da FFprobe: non vanno confrontati direttamente
con la convenzione dell'angolo di MediaMetadataRetriever. Entrambi i file vengono presentati in verticale.
Sul selfie sono stati confrontati preview e fotogramma estratto: stesso campo 9:16,
proporzioni coerenti e sola preview specchiata. La scena posteriore era molto fuori fuoco:
il file è leggibile e verticale, ma manca una prova geometrica precisa con riferimenti nitidi ai bordi.
Non equiparare questo caso alla verifica completa dell'inquadratura posteriore.

È stato inoltre eseguito un take frontale da **50,575 s** senza pausa, con richiesta all'utente
di ruotare il telefono e riportarlo in verticale. La registrazione è rimasta attiva e l'UI 1080×2340
nelle acquisizioni a circa 3, 14, 25, 36 e 47 secondi; stop e salvataggio sono riusciti.
Il file viene decodificato interamente senza errori, ha PTS audio/video strettamente crescenti,
passo massimo video 43,334 ms e audio 21,396 ms. Non è una misura di drift su take lungo.
L'utente ha poi confermato la manovra, ma nel filmato campionato di questo primo tentativo
non era documentato un passaggio completo di 90°. Per evitare una finestra temporizzata troppo breve,
è stata eseguita la successiva prova sincronizzata descritta sotto.

Tutte le sette riprese precedenti sono state confrontate via SHA-256 e sono intatte.
Inventario finale: 8 file completati e nessun file in pending. Nessuna cancellazione.
Le evidenze aggiuntive sono in `.benchmarks/android/20261117T064941Z/portrait/`, escluse da Git.
Resta invariato il difetto di continuità al resume misurato nel take con pausa della sessione precedente.

### Rotazione manuale sincronizzata

La ripresa è stata avviata dal pulsante pubblico Registra, mantenuta attiva fino alla conferma
dell'utente e poi fermata dal pulsante Ferma. L'attesa ha prodotto un file di **687,575 s
(11 minuti e 27 secondi), 1.482.736.025 byte**. Non è stato un benchmark termico pianificato.

Questa volta la rotazione fisica di circa 90° è visibile nel filmato, inclusi i fotogrammi
estratti a 660 e 680 s. Durante la manovra la screenshot `63-synced-stop.png` mostra ancora
UI portrait 1080×2340 e registrazione attiva a 11:26; dopo la finalizzazione,
`64-synced-saved.png` misura 2340×1080 e l'UI torna a seguire l'orientamento attuale.
Il file resta un solo take con matrice portrait costante: la scena ruota dentro il canvas
bloccato, senza cambio delle dimensioni o riavvio del writer. Questo è il comportamento atteso.
La prova chiude il controllo della rotazione manuale sulla camera frontale di questo dispositivo;
non estende automaticamente l'accettazione ad altre camere, versi di rotazione o produttori.

Video: H.264 1920×1080, matrice display +90°, circa 30,001 fps medi, 20.628 pacchetti.
Audio: AAC stereo 48 kHz, 32.229 pacchetti. PTS strettamente crescenti su entrambe le tracce;
intervallo massimo video 43,334 ms, audio 22,251 ms. Non ci sono le discontinuità grandi
del take con pausa, ma la sincronizzazione percettiva e il drift con riferimento A/V non sono ancora misurati.

La prima verifica FFmpeg con time base di output predefinita ha segnalato un DTS duplicato
nel muxer null: due PTS sorgente distinti ricadono nello stesso tick a 1/30 s.
Ripetendo la decodifica con `-fps_mode:v passthrough -enc_time_base:v demux`, il controllo
completo termina con exit code 0 e nessun errore. I due log sono conservati; non classificare
quel messaggio del muxer di verifica come duplicazione dei PTS nel file sorgente.

Il take è salvato e non ci sono registrazioni ancora attive. Inventario finale: **9 riprese
completate, 0 pending**; tutte le otto precedenti conservate byte per byte e verificate via SHA-256.
Le nuove evidenze sono nello stesso percorso `portrait/`, con prefisso `synced-` e screenshot 60–64.
Nessuna modifica al codice applicativo o alle impostazioni di rotazione è stata necessaria per queste prove.

## Prima di iniziare su un telefono

Registrare modello, produttore, build Android, ID camera, versione APK e commit/diff del codice.
Aggiornare in place con `adb install -r`. Se esistono già riprese, conservarne una copia completa
e un inventario prima di diagnostica o pulizia. Il prototipo non dispone di cancellazione automatica.
Usare soltanto lo spazio `capture-prototype`, senza toccare progetti iOS o altre app.

## Prove attraverso i controlli dell'app

1. Negare entrambi i permessi, concederne uno solo, quindi concederli entrambi dalle impostazioni.
   Verificare messaggio comprensibile e apertura senza crash al ritorno. La libreria deve restare consultabile.
2. Registrare 20 secondi con camera posteriore e frontale, in portrait e landscape.
   Inquadrare testo leggibile, un oggetto rotondo e riferimenti ai quattro bordi.
   Confrontare screenshot della preview e fotogrammi del file: campo visivo, orientamento,
   proporzioni, bande nere e mirror. La selfie preview è specchiata, il file no.
3. Provare a ruotare il telefono durante un take. L'orientamento UI resta bloccato fino alla finalizzazione;
   il take successivo deve poter usare il nuovo orientamento. Verificare video e tag di rotazione.
4. Usare pausa/riprendi più volte; premere stop immediatamente dopo start o una pausa.
   Verificare durata, assenza di code mute inattese e controlli disabilitati durante la finalizzazione.
5. Cambiare camera da fermo. Durante la ripresa il cambio non deve essere disponibile.
   Se la seconda camera non espone 1080p SDR, mostrare il limite e permettere di tornare alla prima.
6. Mandare l'app in background durante registrazione e pausa; tornare e riprodurre il risultato.
   Provare chiamata e altra app che usa il microfono: verificare segnalazione delle interruzioni,
   file conservati e nessuna ripresa continuata silenziosamente.
7. Aprire la libreria, riprodurre dall'inizio alla fine, chiudere e riaprire l'app.
   Verificare elenco, seek, audio e assenza di riprese completate mancanti.
8. Provare spazio quasi esaurito e terminazione forzata durante una ripresa isolata.
   Il file fallito deve restare in `pending`, con avviso nella libreria dopo la riapertura.
   Non aspettarsi recovery: non è ancora implementato. Non eliminare questi file come se fossero cache.
9. Registrare 30–60 minuti su due telefoni di produttori diversi. Raccogliere memoria, termica,
   dimensione, durata, PTS effettivi e drift A/V all'inizio e alla fine con riferimenti visivi/sonori.

## Ispezione dei file

Estrarre una copia da una build debug senza modificare il contenuto sul telefono:

```sh
adb -s SERIAL exec-out run-as it.ecamuto.yourec.android.prototype ls files/capture-prototype/completed
adb -s SERIAL exec-out run-as it.ecamuto.yourec.android.prototype cat files/capture-prototype/completed/UUID.mp4 > take.mp4
ffprobe -v error -show_streams -show_format -of json take.mp4
ffmpeg -v error -i take.mp4 -fps_mode:v passthrough -enc_time_base:v demux -f null -
```

Sostituire `SERIAL` e `UUID.mp4` con gli identificativi effettivi.
Archiviare codec, dimensioni, rotazione, pixel format/colore SDR, fps richiesti/medi,
sample rate, canali, durate, errori di decodifica e misure di sincronizzazione.
La sola lettura delle tracce nel prototipo non verifica tutti questi aspetti.

Per ciascun caso conservare screenshot, video, metadati, log pertinenti e risultato
insieme al codice esatto testato. Dopo modifiche a capture/framing/encoder ripetere le prove interessate.
