# Fattibilità del porting Android di YouRec

Analisi del 16 novembre 2026 sul checkout `faf1815`. Valutazione statica del codice e confronto con documentazione primaria Android e dei runtime ML. Nessuna build Android, conversione dei modelli o misura su un telefono Android è stata eseguita: «implementabile» indica una strada tecnica identificata, non una funzionalità già validata.

Piano operativo del 17 novembre: [priorità, difficoltà, fasi e stime](ANDROID-PLAN.md).

## Verdetto e significato di «totale»

**È fattibile realizzare una versione Android con il flusso completo di registrazione, montaggio, elaborazione offline e gestione dei progetti. Non è dimostrata, né promettibile su tutti gli Android, la parità letterale di ogni funzione iPhone.**

| Obiettivo | Valutazione |
| --- | --- |
| Registrare, montare, applicare effetti, trascrivere offline ed esportare | Fattibile; richiede nuovi motori Android e validazione dei modelli. |
| Conservare il comportamento dell'editor e la modifica non distruttiva | Fattibile; il modello temporale e la persistenza vanno tradotti con precisione. |
| Usare tutte le funzioni hardware su qualsiasi Android | Non fattibile come garanzia universale: camere, formati, lenti e codec sono opzionali. |
| Registrare lo stesso Apple Log / Apple Log 2 nativo dell'iPhone | Non è una capacità standard Android equivalente. Un eventuale Log OEM va trattato come profilo diverso. |
| Riprodurre la modalità selfie che sfrutta l'intero sensore frontale compatibile | Dipende dai formati e dal campo visivo esposti dal sensore Android; un crop non ricrea la porzione mancante. |
| Importare su Android la profondità Cinematic Apple con le stesse API | Nessun sostituto diretto identificato: serve un lettore dedicato o una conversione. |
| Aprire ogni ZIP iOS ed esportarlo con identico risultato | Possibile solo dopo lavoro esplicito su formati, font, modelli e profondità; non deriva automaticamente dal JSON. |
| Compilare il progetto Swift attuale per Android | Il checkout non contiene un percorso di build Android; UI e stack multimediale sono Apple. |

La decisione consigliata è avviare il porting con una matrice di capacità e telefoni di riferimento. Se il requisito inderogabile è «ogni funzione identica su ogni telefono», il requisito va cambiato prima di procedere.

## Base dell'analisi

L'applicazione contiene 105 file Swift, 45.360 righe Swift, 771 righe Metal e un componente applicativo C++ di 179 righe, oltre alle dipendenze vendorizzate. Sono presenti 25 sorgenti di test Swift/C++. Questi conteggi includono diagnostica e non misurano direttamente lo sforzo di porting.

I punti principali esaminati sono:

- Acquisizione: `CaptureController`, `CaptureSegmentWriter`, `LiveMicrophone`, `CaptureAudio`, `MultiCameraCapture`, `DualFaceDetector`, `HorizonLevel`, `RecorderZoomViewport`.
- Modello/editor: `Project`, `TrackTimeline`, `TimelineEditing`, `TimelinePaste`, `EffectAnimation`, `Transitions`, `ProjectStore` e relativi controlli.
- Rendering: `MediaRenderer`, `TrackRenderer`, `VideoToolCompositor`, `TransitionRenderer`, `MetalVideoEffects`, shader, `LogColor`, stabilizzazione, Night fix, segmentazione e profondità.
- Audio/ASR: `OfflineAudioPipeline`, processori RNNoise/DeepFilterNet/LavaSR/VoiceFixer, `SubtitleTranscriber`, `SubtitleModelPack`, runtime Qwen e documenti di provenienza.
- Portabilità/salvataggi: `ProjectArchive`, `EditorialTimeline`, `ProjectRecovery`, `RecordingSalvage`, `ExportCheckpoint`, `ImportedFonts`, licenze e script di verifica.

Il codice prevale sui documenti descrittivi quando divergono: il catalogo attuale contiene **24 effetti**, mentre una tabella precedente ne elenca 20; lo schema leggibile arriva a **12**; `ExportCheckpoint` riutilizza sezioni video completate di **12 secondi**, oltre all'audio, anche se il README descrive un comportamento di ripartenza meno evoluto. Il vecchio uploader YouTube compare come codice di rimozione: non è una funzione attiva da portare.

## Inventario funzionale: registrazione

Nella tabella «Sì» significa che non emerge un ostacolo di piattaforma; prestazioni e correttezza richiedono comunque verifica. «Condizionato» significa che la capacità hardware o il risultato non sono garantiti.

| Funzione presente | Esito Android | Lavoro e limite concreto |
| --- | --- | --- |
| Progetti, nuove riprese, append dopo riapertura | Sì | Conservare sorgenti immutabili, ledger di acquisizione e associazione preventiva tra take e progetto. |
| Record, pausa, ripresa, stop; inserimento «record here» | Sì | Segmentazione e timestamp devono preservare la timeline e il singolo passo Undo dell'inserimento. |
| Audio continuo durante cambio frontale/posteriore | Sì, da provare | AudioRecord indipendente dalla sessione camera; writer indipendente; mantenimento dell'ultimo fotogramma durante il cambio. Non garantire uno switch video senza pausa. |
| 1080p e 4K a 30 fps | Condizionato | Negoziare camera, superfici e codificatore insieme; una camera può avere capacità diverse dall'altra. |
| Scoperta lenti e cambio obiettivo | Condizionato | Distinguere camere logiche/fisiche; non tutte le lenti dell'app OEM sono esposte alle app terze. |
| Memoria zoom/fuoco per camera, preset e rampe | Condizionato | Logica trasferibile; limiti zoom, fuoco manuale e traiettoria effettiva dipendono dall'hardware. |
| Autofocus, blocco fuoco, posizione lente e focus peaking | Sì con capacità | Fuoco manuale solo dove disponibile; peaking è uno shader di anteprima, non un vincolo Apple. |
| Mirroring separato tra anteprima e file | Sì | Applicare la trasformazione alla singola camera, senza invertire layout, testo o coordinate dei gesti. |
| Orientamento bloccato per take, anteprima full-frame/fill | Sì | Gestire esplicitamente rotazione sensore, display e canvas; verificare il file salvato. |
| Selfie orizzontale tenendo il telefono verticale | Condizionato | L'attuale percorso usa `supportedDynamicAspectRatios` e configurazione del sensore Apple. Su Android verificare area realmente acquisibile e perdita di campo visivo. |
| Front light con schermo | Sì | UI, luminosità temporanea e ripristino delle preferenze. |
| Stabilizzazione in acquisizione | Condizionato | EIS/OIS e combinazioni con HDR, zoom e doppia camera vanno negoziate; non corrispondono ai nomi delle modalità Apple. |
| Level horizon, limite angolo e crop fisso | Sì con sensori | SensorManager più trasformazione GPU; preservare smoothing, neutralizzazione e sincronizzazione della gravità. |
| Follow face in singola/doppia camera | Sì, qualità da verificare | Sostituire Vision con un detector/tracker locale; mantenere overscan, dead zone e comportamento alla perdita del volto. |
| Due camere simultanee | Condizionato | Enumerare coppie e sessioni supportate; il comportamento attuale parte già da 1080p/30 SDR. |
| Floating rettangolare/circolare, affiancato, sopra/sotto, diagonale | Sì se due feed disponibili | Compositore GPU proprio per crop, maschere, divisori, swap e manipolazione durante il take. |
| Sincronizzazione dei due feed e dell'audio | Condizionato, rischio alto | Validare le basi temporali effettive e il ritardo EIS; code limitate e politica esplicita per un feed fermo. |
| Selezione microfono, ranking persistente e fallback | Sì con limiti di routing | Richiesta di dispositivo e verifica del dispositivo effettivo; gestire identità, riconnessioni e cambi formato. |
| Microfoni integrati, USB, cablati e Bluetooth | Condizionato | Profili Bluetooth, canali, frequenze e routing variano; non trasferire le assunzioni AVAudioSession. |
| Gain −40/+12 dB, rampa 20 ms, meter e clipping | Sì | Elaborazione della copia PCM prima dell'encoding; stesso conteggio campioni e bilanciamento stereo. |
| Protect recording audio | Equivalente parziale | Non esiste qui un percorso verificato equivalente alla preferenza AVAudioSession per i banner di chiamata; gestire perdita/silenziamento del microfono e finalizzazione. |
| Bookmarks durante la registrazione | Sì | Conservare tempo sorgente, etichette e miniature; indipendenti dal cambio camera. |
| Recupero take dopo interruzione/crash | Sì, writer nuovo | Frammenti realmente pubblicati, ricevuta iniziale, riconciliazione e test con terminazione forzata; un MP4 normale non finalizzato non basta. |

Android espone coppie concorrenti tramite `getConcurrentCameraIds()` e combinazioni ammesse per ciascuna camera. Questo non equivale ad avere qualsiasi coppia di lenti disponibile. La separazione tra camera logica e fisica e l'accesso privilegiato delle app OEM sono documentati ufficialmente. [CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager), [Multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera).

Un rischio specifico è il clock: `SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN` non consente confronti accurati con altri sottosistemi o altre camere. La selezione dei dispositivi deve includere questa verifica, oltre al supporto dichiarato alla doppia acquisizione. [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics).

La preferenza del microfono non garantisce il routing effettivo; il sistema può anche silenziare un'app in presenza di un altro utilizzatore prioritario. Il porting deve distinguere registrazione attiva da acquisizione di silenzio imposta dal sistema. [AudioRouting](https://developer.android.com/reference/android/media/AudioRouting), [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input).

## Inventario funzionale: editor, progetti e uscita

| Funzione presente | Esito | Nota di implementazione |
| --- | --- | --- |
| Timeline con thumbnails, waveform, pinch/step zoom e navigazione | Sì | UI Compose/custom drawing e cache progressive; evitare ricostruzioni dell'intera timeline a ogni frame. |
| Taglio, trim, reorder, selezioni, eliminazioni ripple/gap | Sì | Tradurre le regole temporali; riutilizzare i casi limite dei test esistenti. |
| Tracce audio/video indipendenti, Unlink, Move e gruppi laterali | Sì | Non ridurre il modello a una semplice playlist di clip. |
| Overlap, z-order, Placement, crop separato per bordo, Fit/Fill | Sì | Composizione di più sorgenti nello stesso istante; numero decoder simultanei da limitare e misurare. |
| Cut/paste, copie indipendenti e snapping | Sì | Clonare gli ID e rispettare la semantica degli effetti e dei sottotitoli. |
| Undo/Redo persistente e registrazioni protette dallo storico | Sì | Conservare snapshots immutabili, cursore e capture ledger, comprese le risorse presenti solo in Redo. |
| Import multiplo ordinato, foto come clip e take inutilizzati | Sì | Picker Android, copie private e durata delle foto. Stessi formati di input da verificare separatamente. |
| Import audio, mix di layer, volume e fade alle estremità | Sì | Pipeline PCM con mapping del tempo, silenzi e mix corretti. AIFF/CAF o formati meno comuni possono richiedere decoder aggiuntivi. |
| Censor audio con beep, onde, pulsazioni e inviluppi | Sì | Mantenere muto l'originale anche nei vuoti del beep; fondamentale per la compatibilità dei progetti. |
| Pauses con soglia, durata, padding e decisioni ricordate | Sì | Algoritmo locale e mapping di timeline, senza dipendenza AI obbligatoria. |
| Velocità 0,25–4× con pitch conservato | Sì | Nuovo time-stretch e clock comune audio/video; validare ritmo, transienti e campioni ai bordi. |
| Review 1–3× e ascolto Bypass/Enhanced | Sì | Stato di preview distinto dall'edit; il matching del livello non deve modificare l'export. |
| Crop, rotazione, mirror e cambio forma progetto | Sì | Preservare canvas di riferimento e ordine delle trasformazioni. |
| Titoli, stili, gradienti, immagini e manipolazione diretta | Sì | Rendering testo Android o motore condiviso; usare identico layout in preview/export. |
| Font TTF/OTF/TTC/OTC e font inclusi nel progetto | Sì, con validazione | Gestire collezioni, indici delle facce, pesi variabili e font identificati dal contenuto. I font di sistema Apple richiedono sostituzioni autorizzate. |
| Animazioni A/B, curve e keyframe | Sì | Preservare clock sorgente/output, wrap degli angoli e interpolazione dei parametri discreti. |
| 9 tipi di transizione e controllo audio indipendente | Sì con motore dedicato | Dissolve, fade color, wipe, split, Hitchcock, whip, flash cut, still, defocus; non basta concatenare i file. |
| Stabilizzazione offline Off/Light/Standard/Strong | Sì | Stimatore C++ riutilizzabile via NDK; decode, resize, smoothing e applicazione GPU da collegare. |
| Sottotitoli offline, revisione testo, raggruppamento e styling | Sì con runtime ASR nuovo | Conservare ownership dei vocaboli sulle tracce, editing in tempo sorgente, fingerprint e Undo. |
| Rinomina, miniatura, duplicazione e Recently Deleted | Sì | Persistere in storage privato, con pubblicazione atomica e recupero dell'indice. |
| Original recordings e cover JPEG a piena risoluzione | Sì | Separare originali, render e frame estratti dall'edit. |
| ZIP completi, checksum, relink e import danneggiati | Sì con compatibilità esplicita | Leggere anche tutte le risorse dello storico; proteggere percorsi e limiti di estrazione come nel codice attuale. |
| OpenTimelineIO verso editor esterni | Sì | Il progetto genera JSON OTIO; non necessita di un SDK Apple. Restano i limiti dichiarati per effetti/mix personalizzati. |
| Export MP4 H.264/HEVC, risoluzioni e 24/25/30/50/60 fps | Condizionato ai codec | Le opzioni sono trasferibili, ma dimensioni, profili e throughput devono essere negoziati. |
| Ricevute export, checksum, riuso risultato e checkpoint | Sì | Cache versionata; preservare continuità AAC e timestamp anche unendo sezioni video. |
| Condivisione, salvataggio in galleria, chime e Cancel | Sì | Share sheet, MediaStore, progress e gestione del ciclo di vita Android. |

Media3 offre Composition, Transformer e anteprima con CompositionPlayer. La documentazione consultata dichiara ancora il crossfade audio/video non supportato direttamente nelle Composition e CompositionPlayer sperimentale. La conseguenza progettuale è prevedere compositing/mixing specifici o prerender delle transizioni, senza delegare tutta la semantica dell'editor alla libreria. [Composition](https://developer.android.com/media/media3/transformer/composition), [CompositionPlayer](https://developer.android.com/media/media3/transformer/compositionplayer).

## Tutti i 24 effetti video

Le formule e i parametri sono riutilizzabili come specifica. I kernel Metal e l'orchestrazione Core Image devono essere riscritti; alcuni effetti includono analisi temporale, asset e audio di background oltre allo shader.

| ID del codice | Fattibilità e dipendenza principale |
| --- | --- |
| `colorCorrection` | Sì: pipeline colore, esposizione, contrasto e bilanciamento. |
| `silentTint` | Sì: trattamento colore GPU. |
| `threeStrip` | Sì: trattamento colore GPU. |
| `bleachBypass` | Sì: trattamento colore GPU. |
| `dayForNight` | Sì: trattamento colore GPU. |
| `colorIsolation` | Sì: selezione colore GPU. |
| `noir` | Sì: trattamento colore GPU. |
| `expressionism` | Sì: trattamento colore GPU. |
| `quantization` | Sì: conservare palette, campionamento e spazio colore. |
| `rgbOffset` | Sì: offset e sampling dei canali. |
| `negativeStrobe` | Sì: riprodurre clock, fase e numero di flash in preview/export. |
| `kaleidoscope` | Sì: coordinate, settori, trasformazioni e animazioni. |
| `halation` | Sì: blur multipass e composizione; costo GPU da misurare. |
| `sixteenMM` | Sì: grana e modulazioni temporali riproducibili. |
| `superEight` | Sì: grana e modulazioni temporali riproducibili. |
| `chromaKey` | Sì: key, despill, clean plate e composizione premoltiplicata corretta. |
| `autoBackground` | Sì con nuovo modello: maschera persona, filtro temporale, background immagine/video e relativo audio. Qualità dei bordi non identica a Vision per definizione. |
| `dollyZoom` | Live condizionato allo zoom/tracking; editor fattibile con segmentazione. La variante che usa profondità Cinematic richiede un percorso aggiuntivo. |
| `crashZoom` | Sì: tracking e zoom live condizionati all'hardware; trasformazione editor trasferibile. |
| `widescreen` | Bande/framing sì; bokeh ovale basato su profondità condizionato alla disponibilità di vere mappe di profondità. |
| `freezeFrame` | Sì: inserzione strutturale di un fermo ad alta risoluzione, non soltanto shader. |
| `stepPrinting` | Sì: accumulo/risampling temporale, con gestione dei frame passati. |
| `undercranking` | Sì: cadenza, velocità e audio; mantenere distinta anteprima live ed elaborazione editor. |
| `nightFix` | Fast trasferibile a shader; Medium/Precise richiedono optical flow sostitutivo, fusione di 3/9 frame, cache e validazione visiva. |

Media3 espone un'interfaccia per programmi shader personalizzati. Per l'auto background esistono modelli di segmentazione persona utilizzabili su video Android; questo prova la disponibilità del meccanismo, non l'equivalenza delle maschere Vision. [GlShaderProgram](https://developer.android.com/reference/androidx/media3/effect/GlShaderProgram), [MediaPipe Image Segmenter](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter?tab=Android+(Kotlin)).

Night fix non è un modello AI da convertire: `NightFix.swift` seleziona optical flow classico Vision revision 1; gli shader `nf_*` implementano fusione e pulizia. Sostituire il flow con un algoritmo diverso conserva la funzione ma richiede nuove soglie e confronti su occlusioni, cambi scena, rumore e scie. Anche la cache usa Main10 HEVC: su hardware senza encoder adeguato va cambiato il formato intermedio.

La stabilizzazione offline è un caso più favorevole: `StabilizationMotion.cpp` e il suo header usano una superficie C/C++ semplice, separata dal resize Accelerate e dalla lettura AVFoundation. L'algoritmo non dipende dal giroscopio dell'iPhone. Va conservato il campionamento sui PTS originali, così i frame VFR o a 24/25/60 fps non ricevono correzioni inventate.

## HDR, Log e profondità: le eccezioni alla parità esatta

**HDR.** L'app registra HLG/BT.2020 a 10 bit e conserva un percorso HDR per materiale non trasformato in look SDR dagli effetti creativi. Android dispone di HLG10 e Media3 documenta editing HDR da API 33 su dispositivi con encoding adeguato. Serve una catena verificata camera → texture → shader → preview → encoder: niente passaggi impliciti a 8 bit o conversioni colore doppie. La scelta consigliata è Android 13/API 33 come base di prodotto per ridurre le varianti, non perché l'editor SDR richieda quella versione. [DynamicRangeProfiles](https://developer.android.com/reference/android/hardware/camera2/params/DynamicRangeProfiles), [Formati di Transformer](https://developer.android.com/media/media3/transformer/supported-formats).

**Apple Log.** Il codice seleziona direttamente `AVCaptureColorSpace.appleLog`/`appleLog2`. La presenza di HDR o di un encoder Main10 su Android non dimostra un accesso equivalente al segnale della camera. Un eventuale profilo Log OEM va rilevato, descritto e trasformato secondo la sua curva e le sue primarie. Applicare una curva «flat» a un video già elaborato non ricostruisce la gamma persa. L'importazione di file Apple Log è un problema distinto: le trasformazioni matematiche originali in `LogColor.swift`, le LUT e il recupero alte luci sono traducibili, se il decoder mantiene valori e metadati corretti. L'assenza di una capacità Android equivalente è una conclusione di questo confronto, non una prova che nessun produttore possa offrire un proprio SDK.

**Cinematic.** `VideoEffectDepth.swift` usa `CNAssetInfo`, traccia disparity, decoder Apple e `CNRenderingSession.FrameAttributes` per la messa a fuoco. Un normale decoder del video visibile non assicura lettura della profondità. Le strade sono: conversione su Apple verso mappe/metadata portabili, implementazione dedicata del formato con verifica dei campioni, oppure una nuova stima monoculare. Quest'ultima produce una nuova profondità e non soddisfa la richiesta di utilizzare gli stessi dati originali. Il fallback di Dolly Zoom alla segmentazione esiste già; il bokeh ovale di Widescreen non diventa equivalente sostituendo profondità con una maschera binaria.

## Audio e sottotitoli offline

| Componente attuale | Portabilità concreta | Verifica necessaria |
| --- | --- | --- |
| Gain, low-cut, EQ, fade, beep, pause detector | Formule e logica traducibili; estrazione in C++ opzionale. | Bypass esatto, campioni, stereo, transienti e mapping temporale. |
| RNNoise v0.1 | Sorgenti C e adapter presenti, compilabili tramite NDK dopo integrazione. | Stato per canale, hop 480 a 48 kHz, delay, drain e cancellazione. |
| DeepFilterNet3 | Motore attuale Swift/Core ML/Accelerate; usare runtime nativo upstream o esportazione per runtime Android. | Stato ricorrente, lookahead, filterbank ERB corretta, output e prestazioni. |
| LavaSR v2 | DSP già in C++; rete attuale Core ML da riesportare dai pesi originali. | Supporto operatori, padding, precisione, finestre e ricostruzione. |
| VoiceFixer | DSP C++ riutilizzabile; rete e vocoder da esportare per Android. Nel checkout il modello Core ML generato non è presente. | RAM di picco, tempo, operatori e qualità; il benchmark iPhone non si trasferisce. |
| Qwen3-ASR-0.6B | Sostituire il sottoinsieme MLX Swift. Esiste Qwen3-ASR ONNX con API Kotlin/Java in sherpa-onnx. | Confrontare lingue, accuratezza, decoder, consumo RAM e durata su audio lungo. |

Qwen è quindi meno incerto di una conversione da costruire da zero: esiste un percorso upstream documentato per lo stesso modello. Il pack MLX a 4 bit di YouRec, da 712.705.305 byte, non coincide però con gli artefatti ONNX: dimensione del download e memoria non possono essere promesse uguali. [Qwen3-ASR in sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html), [piattaforme sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

Per LavaSR e VoiceFixer, ONNX Runtime Mobile è un candidato da verificare; l'esistenza del runtime Android non garantisce che ogni operatore del modello sia accelerato. Partire da una baseline CPU confrontabile, poi provare gli execution provider adatti. Non rendere la disponibilità di una NPU specifica un prerequisito implicito della correttezza. [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/).

I sottotitoli attuali hanno tempi parola stimati da pause e lunghezza, senza forced alignment né diarizzazione. Conservare questa funzione non richiede aggiungerne di nuove. Va però sostituito anche `NaturalLanguage.NLTokenizer`, preservando segmentazione e punteggiatura nelle lingue senza spazi. Sostituire Qwen con un ASR differente può conservare la categoria funzionale, ma cambia il comportamento rispetto all'obiettivo di parità.

## Compatibilità dei progetti: lavoro nascosto ma necessario

Il formato è prevalentemente JSON più media e sidecar, quindi adatto allo scambio. Esistono tuttavia vincoli che una semplice riscrittura delle classi Kotlin non risolve:

1. **Modelli legati al runtime.** `AudioModel` salva hash dei pesi e runtime Core ML; `AudioEffect.supported` confronta l'intera identità. Un modello Android equivalente richiede un mapping esplicito e versionato. Altrimenti un progetto importato può contenere filtri che la nuova app rifiuta, oppure finire per essere modificato silenziosamente. Le cache devono includere il backend realmente eseguito.
2. **Maschere catturate.** `VideoEffectCaptureAnalysis.swift` comprime maschere e storia temporale con LZFSE. Sono risorse necessarie per riprodurre gli effetti registrati, non tutte semplici cache eliminabili. Su Android si può integrare la libreria C LZFSE, verificando compatibilità e limiti, oppure introdurre un formato nuovo leggibile da entrambe le app. [Libreria LZFSE](https://github.com/lzfse/lzfse).
3. **Hash degli snapshot.** `EditSnapshot.data()` produce JSON ordinato; il nome del file è il suo SHA-256. Conservare i byte importati e i relativi riferimenti è più sicuro che presumere che due encoder producano JSON identico. Per i nuovi snapshot va stabilito il contratto di serializzazione.
4. **Tipi Swift Codable.** Enum con payload, dizionari con chiavi non banali, UUID, date e numeri devono essere verificati con fixture reali. Le date usano i default JSONEncoder/Decoder, non una convenzione ISO da supporre.
5. **Tempi e geometrie.** `EditTime` usa 240.000 tick al secondo e intervalli con estremo finale escluso. Preservare questa base, arrotondamenti cumulativi, velocità, fermo immagine, canvas e coordinate top-left/transform, compresi tutti gli stati Undo/Redo. Convertire indiscriminatamente tutto in millisecondi introdurrebbe differenze di montaggio.
6. **Font.** I font OFL vendorizzati sono una base condivisibile; i font di sistema Apple e il layout CoreText non sono automaticamente disponibili. Per la stessa impaginazione servono font comuni e regole condivise di line break, baseline, fallback e shaping.
7. **Codec/container.** Decoder Android e AVFoundation non hanno necessariamente lo stesso insieme di formati supportati; trattare esplicitamente audio AIFF/CAF, immagini particolari e video importati, oltre a HEVC/HLG/Log.
8. **Profondità Cinematic.** La piena fedeltà di un progetto che dipende da disparity Apple rimane un requisito aperto finché non viene scelta e verificata una delle strade descritte.
9. **OTIO.** È un'interoperabilità editoriale distinta dal progetto YouRec completo: l'OTIO attuale non promette di ricreare ogni effetto personalizzato nemmeno su desktop.

## Architettura proposta

La proposta seguente è una valutazione progettuale, non un'architettura già implementata o misurata.

| Area | Scelta iniziale |
| --- | --- |
| UI, accessibilità, gesti, lifecycle | Kotlin + Jetpack Compose, drawing dedicato per timeline e controlli. |
| Core editor | Modello indipendente dal player; traduzione Kotlin delle regole pure oppure nucleo condiviso C++ solo dove conviene. |
| Camera | Camera2 per controllo avanzato e timestamp; valutare CameraX per ridurre gestione dei dispositivi dove non limita i requisiti. |
| Acquisizione audio | AudioRecord indipendente dal video; DSP pesante via JNI. Oboe è un'alternativa se il core audio diventa nativo. |
| Rendering | OpenGL ES/EGL e shader propri; valutare Vulkan per elaborazioni selezionate, senza duplicare subito tutti i backend. |
| Player ed export | Media3 come infrastruttura; estensioni proprie per transizioni, mapping e compositing non coperti. MediaCodec per encoding e percorsi specifici. |
| Recovery del video | Valutare FragmentedMp4Muxer Media3 e/o segmenti finalizzati; verificare cosa resta leggibile dopo kill reale. |
| ML | RNNoise NDK; sherpa-onnx per Qwen; runtime DeepFilterNet e ONNX per restauro dopo proof of concept. |
| Persistenza | JSON versionato, file immutabili, pubblicazione atomica, checksum e indice recuperabile. |

La piattaforma dispone di un muxer fMP4, utile per progettare il recupero; l'effettiva resistenza ai crash dipende anche da flushing, pubblicazione e decoder, quindi va provata. [FragmentedMp4Muxer](https://developer.android.com/reference/androidx/media3/muxer/FragmentedMp4Muxer). Per un core audio nativo Android indica Oboe/AAudio. [Audio NDK](https://developer.android.com/ndk/guides/audio).

Condividere lo stesso grafo di rendering tra preview ed export è un requisito del prodotto da mantenere. Evitare il percorso camera → bitmap CPU → UI → encoder: moltiplica copie e sincronizzazioni. La UI deve ricevere stato e controlli, mentre il video resta nel percorso di texture/superfici dove possibile.

Flutter/React Native potrebbero ospitare una UI, ma i blocchi più costosi resterebbero motori nativi, plugin e bridge da sviluppare. Non sono una scorciatoia dimostrata per il porting di questo progetto. Anche mantenere Swift per parti pure non elimina la riscrittura delle integrazioni Apple.

L'app attuale sospende la camera quando passa in background: non serve introdurre registrazione invisibile per dichiarare parità. Per export e ML prolungati va progettato il lavoro Android con notifica, cancellazione, checkpoint e rispetto dei limiti. In particolare, Android documenta limiti temporali per i servizi `mediaProcessing` quando l'app è in background; WorkManager non rende illimitato il lavoro multimediale. [Limiti dei foreground service](https://developer.android.com/develop/background-work/services/fgs/timeout).

## Riuso reale e dimensionamento

Riutilizzabili direttamente, dopo build e integrazione NDK: RNNoise C, `StabilizationMotion.cpp`, `LavaDSP.cpp`, `VoiceFixerDSP.cpp`, dati palette e asset con licenza adatta. Riutilizzabili come specifica o con traduzione: modello del documento, regole di montaggio, matematica di effetti, test e fixture. Da sostituire: SwiftUI/UIKit, AVFoundation/AVKit, Core Image/Metal, CoreText, Vision, Accelerate, Core ML e il runtime Qwen MLX attuale.

Questo è un secondo client nativo con un nuovo backend multimediale. Non è ragionevole ricavare una percentuale affidabile di codice riusabile o un preventivo dal conteggio delle righe.

Per pianificazione preliminare, riservare **4–6 settimane di proof of concept** e ragionare poi in termini di **molti mesi di sviluppo e verifica**, non settimane per il porting completo. Come ordine di grandezza non vincolante, 12–24 mesi/persona per una versione ampia e stabilizzata possono essere un budget iniziale da rivedere: conversioni ML, profondità Cinematic, numero di telefoni e fedeltà dei progetti possono cambiarlo sensibilmente. Non sommare o dividere linearmente questo numero per stimare una data di consegna.

Le licenze presenti non mostrano un impedimento tecnico alla riscrittura: codice originale MIT, dipendenze con rispettive condizioni e pesi VoiceFixer attribuiti CC BY 4.0. I nuovi artefatti devono mantenere provenienza e avvisi. Nome e icona YouRec sono riservati nel LICENSE; SF Symbols e font Apple non vanno considerati asset Android liberamente redistribuibili.

## Prove che chiudono le incertezze

Prima di impegnarsi sulla parità completa, scegliere almeno un dispositivo di riferimento e ampliare la verifica a famiglie di SoC/OEM diverse. Le configurazioni esatte vanno rilevate, senza inferirle dalla sola fascia di prezzo o dalla presenza di più obiettivi.

1. **Acquisizione:** take lungo con cambi camera, pausa/ripresa, microfono USB/Bluetooth e fallback; misurare continuità PCM, deriva A/V, frame persi, ritardi, memoria e termica. Ripetere con le coppie concorrenti e i layout se disponibili.
2. **Rendering/editor:** progetto con overlap, speed, tutti i tipi di transizione, LUT, testo e maschere; confronto preview/export, PTS/durata e colore SDR/HLG. Night fix su rumore, movimento, occlusioni e cambi scena.
3. **ML:** stessa fixture parlata per RNNoise, DeepFilterNet3, LavaSR, VoiceFixer e Qwen; misurare memoria, durata, campioni, stato stereo, cancellazione/ripresa e qualità. Download di un pack Android verificato e funzionamento in modalità aereo.
4. **Scambio e recovery:** corpus di ZIP reali con schemi, Undo/Redo, font, LZFSE, censura e modelli; andata/ritorno tra piattaforme e verifica che nessun effetto abilitato venga ignorato. Kill durante registrazione, salvataggio, import ed export e prove di spazio insufficiente.
5. **Geometria fisica:** preview e file finale da ogni camera interessata, rotazione, crop, mirror, horizon e stabilizzazione. Seguire lo stesso principio di `CAMERA-QA.md`: una build riuscita o un emulatore non dimostrano che l'inquadratura salvata sia corretta.

L'esito di queste prove consente di promettere una parità funzionale definita per dispositivi e formati concreti. Restano esclusioni da esplicitare o problemi da risolvere, prima di usare «porting totale» in senso letterale: acquisizione Apple Log, area speciale del sensore frontale Apple, lettura della profondità Cinematic e comportamento delle interruzioni di sistema.
