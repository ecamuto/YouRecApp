# Piano Android: priorità, difficoltà e condizioni di consegna

17 novembre 2026. Basato sull'[analisi del checkout faf1815](ANDROID-PORTING.md). Le stime seguenti definiscono il perimetro; l’avanzamento verificato è riportato separatamente qui sotto.

Implementazione avviata nel fork: [primo prototipo di acquisizione](../android/README.md).
Per i risultati effettivi e le prove ancora necessarie, consultare [ANDROID-QA.md](ANDROID-QA.md).

## Aggiornamento implementazione 0.3

Cambio camera durante il take implementato con compositore GPU persistente e audio
indipendente. Build/test/lint passano; prove brevi sul Samsung completate nelle
due direzioni, anche in pausa, con confronto preview/file e misure audio.
Il passaggio mantiene un fermo immagine di circa un secondo. Restano prove lunghe,
secondo OEM e qualità/sincronismo A/V. Comportamento, misure e anomalia audio conservata: [ANDROID-SWITCH.md](ANDROID-SWITCH.md).

## Avanzamento verificato della fase 0

Il prototipo 0.2 usa Camera2/MediaCodec e AudioRecord indipendente. Il clock comune
rimuove gli stessi intervalli di pausa dalle due tracce; le prove brevi sul Samsung
SM-A256B mostrano PTS continui al resume e assenza del tono riprodotto durante la pausa.
Registrazione e file reali sulle due camere, libreria, stop da pausa e background
sono stati collaudati. Dettagli: [motore di acquisizione](ANDROID-CAPTURE.md).

La fase 0 resta **parziale**: misure di drift/termica su 30–60 minuti, secondo
produttore, interruzioni audio e recovery sono ancora da provare o implementare.
Verificati spegnimento schermo e terminazione forzata durante un take: il primo
salva il file, la seconda conserva i byte ma lascia il take non riproducibile.
Riapertura, avviso e nuova ripresa funzionano; il recupero resta aperto.
Evidenze: [ANDROID-INTERRUPTIONS.md](ANDROID-INTERRUPTIONS.md).
I prototipi rendering/export e conversione ML non sono iniziati.
Il cambio camera attivo è implementato e collaudato nelle prove brevi del prototipo
0.3; le misure 0.2 descritte qui restano separate dal nuovo percorso GPU.

## Obiettivo e ipotesi

Realizzare prima una versione Android utile per registrare, montare ed esportare, poi ampliare la parità funzionale con iPhone. Tenere visibili le differenze, senza esportare silenziosamente progetti con effetti mancanti.

Ipotesi iniziali, modificabili senza riscrivere il prodotto:

- Un programmatore esperto a tempo pieno, con competenze Android e multimediali; le conversioni ML possono richiedere supporto specialistico.
- Kotlin/Compose per UI e modello; componenti C/C++ esistenti tramite NDK; infrastruttura Media3 con estensioni proprie dove necessarie.
- Android 13/API 33 come base proposta per contenere le varianti. Questo non garantisce HDR, doppia camera o tutte le lenti.
- Due telefoni fisici di riferimento di produttori diversi all'inizio; ampliamento ad almeno quattro configurazioni prima di un rilascio ampio. Nessun acquisto o modello specifico viene deciso da questo piano.
- Baseline iniziale 1080p, 30 fps, SDR, H.264/AAC. 4K, HDR e alta frequenza arrivano con capacità e test dedicati.
- Nessuna modifica obbligatoria al client iOS nella fase iniziale. Un cambiamento condiviso del formato progetto va progettato separatamente, mantenendo la compatibilità.

**Basso rischio non significa poco lavoro.** Le funzioni descritte come semplici lo diventano dopo che acquisizione, rendering, audio e persistenza sono funzionanti. Le durate sono stime di pianificazione, non risultati di misure né promesse di consegna.

## 1. Attività con poche incognite tecniche

Queste parti hanno comportamenti chiari nel codice e non richiedono ricerca o conversione di modelli.

| Attività | Perché è relativamente prevedibile | Dipendenza da completare |
| --- | --- | --- |
| Elenco progetti, nomi, miniature, preferenze, Recently Deleted | UI e operazioni locali con regole note | Storage privato e modello documento |
| Duplicazione, condivisione e accesso agli originali | Copie e risorse già identificate dal progetto | Gestione errori/spazio e proprietà dei file |
| Bookmark, selezioni e navigazione tra punti | Dati e calcoli temporali deterministici | Clock e miniature |
| Gain, low-cut, EQ e generazione beep | DSP matematico già descritto dal codice | PCM decodificato e pipeline audio |
| Parametri di crop/mirror, trasformazioni e overlay immagine | Geometria trasferibile | Compositore e coordinate coerenti |
| Pannelli parametri, preset, slider e animazioni A/B | Stato e interpolazioni trasferibili | Mapping del tempo e rendering |
| Effetti colore semplici e RGB Offset | Formule GPU note, senza modelli neurali | Primo shader funzionante in preview/export |
| Front light e mirroring separato | Comportamento UI/trasformazioni ben definito | Lifecycle e corretta geometria della camera |
| Esportazione JSON OTIO di un montaggio supportato | Generazione dati indipendente da Apple | Modello timeline e test di import esterno |

In questo gruppo rientrano, per la parte matematica, Color Correction, Silent Tint, Three-Strip, Bleach Bypass, Day for Night, Color Isolation, Noir, Expressionism, RGB Offset e Kaleidoscope. Negative Strobe è semplice come trasformazione, ma va verificato sul clock corretto. Le bande di Widescreen sono semplici; il suo bokeh con profondità non appartiene a questo gruppo.

Non prevedo grandi ostacoli di piattaforma qui. Prevedo comunque lavoro su gesti, leggibilità, accessibilità e persistenza: non basta disegnare schermate simili.

## 2. Attività impegnative ma con una strada chiara

| Attività | Difficoltà effettiva | Trattamento nel piano |
| --- | --- | --- |
| Registrazione singola camera SDR con audio | Orientamento, permessi, encoder, timestamp e lifecycle | Costruire subito una base verificabile su hardware |
| Timeline con split, trim, reorder e waveform | Coerenza di UI, modello e tempi; prestazioni sui progetti lunghi | Prima versione sequenziale, poi tracce indipendenti |
| Undo/Redo persistente e append protetto | Stato durevole e cronologia senza perdita delle nuove riprese | Implementare presto il modello definitivo, anche se la UI iniziale è ridotta |
| Tracce indipendenti, Unlink, Move e overlap | Mapping delle sorgenti, gap, layer, decoder concorrenti | Espansione dell'editor dopo il primo percorso completo |
| Titoli e sottotitoli già trascritti | Shaping, font, line break e rasterizzazione | Font condivisibili; stesso layout tra preview/export |
| Velocità con pitch preservato | Time-stretch, durata e sincronizzazione; confini tra clip | Testare prima un solo clip, poi mix e transizioni |
| Transizioni | Due sorgenti simultanee più audio, bordi e timing | Prototipo anticipato; aggiunta graduale dei nove tipi |
| LUT, quantizzazione/palette, halation e texture pellicola | Sampling, spazio colore, passaggi GPU e consumo | Una catena comune; misurare combinazioni, non solo effetti isolati |
| Freeze Frame, Step Printing e Undercranking | Cambiano il campionamento temporale, non soltanto i pixel | Collegarli al clock dell'editor e alla semantica audio |
| Chroma Key | Alpha, despill, clean plate e audio/video di sfondo | Prima sfondo immagine; poi video e relativo audio |
| RNNoise | Nucleo C disponibile; resta l'integrazione | Primo filtro neurale da portare, con stato per canale e delay verificato |
| Stabilizzazione offline | Nucleo C++ riutilizzabile; decode e trasformazioni nuovi | Portare dopo il rendering base; mantenere i PTS sorgente |
| Night fix Fast | Shader spaziali, colore e prestazioni | Separarlo dalle modalità temporali |
| ZIP e progetti Android | Copie, checksum, storico e gestione import interrotti | Prima scambio Android–Android; iOS gestito per sottoinsiemi espliciti |
| Export H.264/AAC affidabile | Sincronizzazione, file completo, spazio e cancellazione | Requisito della prima release, non rifinitura finale |

Media3 è un aiuto, ma il piano riserva lavoro proprio per compositing e mixing: la documentazione consultata riporta ancora il crossfade audio/video tra i limiti delle Composition. [Media3 Composition](https://developer.android.com/media/media3/transformer/composition).

## 3. Attività ad alto rischio o con costo incerto

| Attività | Incognita da risolvere | Decisione se il prototipo non è sufficiente |
| --- | --- | --- |
| Cambio camera durante il take con audio continuo | Riconfigurazione, clock e mantenimento del writer su OEM diversi | Cambio disponibile solo da fermo nella prima release; funzione live resta da completare |
| Due camere contemporanee | Coppie esposte, formati, sincronizzazione e ritardi | Disponibilità limitata alle configurazioni validate; niente promessa universale |
| 4K/HDR con effetti | Pipeline 10 bit, metadati, GPU, decoder/encoder e termica | SDR di base; HDR attivato solo dopo verifica dell'intera catena |
| Microfoni esterni e fallback durante il take | Routing effettivo, Bluetooth, variazioni formato e possibili buchi | Supporto per percorsi provati; avviso/interruzione gestita per gli altri |
| Face follow e Auto Background | Qualità e velocità dei sostituti Vision, stabilità temporale | Rinviare la funzione o dichiararne una modalità limitata; non equiparare una maschera scadente all'originale |
| Dolly/Crash Zoom live | Tracking e risposta reale di zoom/lente | Prima eventuale variante editor; controlli live solo su hardware provato |
| Qwen3-ASR offline | RAM, velocità, formato del modello e qualità | Pack opzionale; rinvio se il telefono di riferimento non regge. Un altro ASR richiede una scelta esplicita di prodotto |
| DeepFilterNet3 | Stato ricorrente, DSP e backend Android | RNNoise rimane la prima opzione; modello aggiunto dopo verifica autonoma |
| LavaSR e soprattutto VoiceFixer | Conversione della rete/vocoder, operatori, memoria e qualità | Nessuna data vincolante prima di una conversione misurata; conservare filtri tradizionali/RNNoise |
| Night fix Medium/Precise | Nuovo optical flow, fusione 3/9 frame e cache video | Pubblicare inizialmente Fast se valido; le altre modalità rimangono separate |
| Recupero da crash durante la ripresa | Frammenti effettivamente leggibili e pubblicati, non solo writer configurato | Ripensare segmentazione/muxer prima di dichiarare acquisizione affidabile |
| Fedeltà di ogni progetto iOS | Runtime/hash, LZFSE, font, codec, profondità e intero storico | Import guidato dei soli casi supportati; nessuna perdita silenziosa di effetti, soprattutto censura audio |

Android espone combinazioni concorrenti da interrogare a runtime, non una garanzia su qualsiasi coppia di camere. Il layout PiP è affrontabile una volta ottenuti due feed corretti; è l'acquisizione dei due feed a costituire il rischio principale. [CameraManager](https://developer.android.com/reference/android/hardware/camera2/CameraManager).

Per Qwen3-ASR esistono già supporto Android e API Kotlin/Java in sherpa-onnx. Questo riduce il lavoro di implementazione del runtime; non stabilisce il consumo o la velocità sul telefono scelto. [Qwen3-ASR in sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/index.html).

Il recupero di file e la cronologia sono difficili per correttezza, anche se non richiedono nuove tecniche. Sono inclusi tra i rischi perché perdere una ripresa può rendere inutilizzabile un'app con molte altre funzioni già implementate.

## 4. Funzioni da lasciare fuori da una promessa iniziale di parità

| Funzione | Impegno onesto |
| --- | --- |
| Acquisizione Apple Log / Apple Log 2 nativa | Non promessa. Valutare separatamente import Apple Log e profili Log Android/OEM realmente accessibili. |
| Modalità speciale del sensore selfie Apple | Solo un equivalente sui sensori Android che espongono formati/campo visivo sufficienti. Nessuna equivalenza universale. |
| Lettura della profondità Cinematic Apple e bokeh dipendente da essa | Ricerca separata su lettura/conversione. La stima di profondità nuova non equivale ai dati originali. |
| Preferenza iOS di protezione dai banner di chiamata | Gestione Android delle interruzioni; comportamento identico non promesso. |
| Risultato pixel-identico con font di sistema Apple o modelli/backend diversi | Non promesso. Definire fedeltà visiva e tolleranze, mantenendo precisione temporale e integrità dei dati. |

Queste esclusioni non impediscono un'app Android utile. Impediscono di descriverla come copia identica al 100% senza ulteriori decisioni e prove.

## 5. Sequenza di lavoro e stime

Le fasi sono prevalentemente sequenziali per il singolo sviluppatore ipotizzato. All'interno di ogni fase rientrano test e correzioni; la verifica su più dispositivi cresce progressivamente. Gli intervalli non si applicano ai soli giorni di scrittura del codice.

| Fase | Durata indicativa | Risultato verificabile |
| --- | --- | --- |
| 0 — Ridurre le incognite | 4–6 settimane | Prototipi acquisizione/switch, compositing/export con una transizione e inferenza Qwen/VoiceFixer; decisioni sui limiti |
| 1 — Prima app utilizzabile | Altre 10–14 settimane | Registrazione singola SDR, gestione progetti, montaggio sequenziale, audio base, crop/titolo, export e ripristino del progetto |
| 2 — Editor esteso | Altre 12–20 settimane | Tracce indipendenti, overlap, transizioni, velocità, immagini/font, LUT/effetti selezionati, RNNoise e stabilizzazione offline |
| 3 — Funzioni avanzate selezionate | Altre 12–24 settimane | Sottotitoli ASR e funzioni hardware/AI che hanno superato le prove, consegnate separatamente |
| 4 — Consolidamento del rilascio ampio | Altre 6–10 settimane | Compatibilità dispositivi, progetti lunghi, recovery, spazio, termica, accessibilità e regressioni di integrazione |

La somma indicativa è 44–74 settimane di lavoro a tempo pieno per il perimetro selezionato; interruzioni, ricerca e ampliamenti possono portare oltre. Per pianificare una versione ampia e stabilizzata manterrei una disponibilità di **12–24 mesi/persona**, rivedendola dopo la fase 0. I casi esclusi sopra non sono inclusi come risultati garantiti in questo intervallo.

La prima app utilizzabile può quindi essere un obiettivo di **circa 4–6 mesi**, dopo la verifica dei prototipi e con perimetro contenuto. Una demo anticipata non ha lo stesso significato di una release affidabile. Con più persone si possono separare UI/editor e motore multimediale/ML, ma integrazione e prove fisiche non si riducono dividendo semplicemente i mesi per il numero di sviluppatori.

### Fase 0: cosa provare prima di costruire tutte le schermate

Tre prototipi piccoli, con file e misure come risultato, riutilizzando solo ciò che si dimostra adatto:

1. **Acquisizione:** 1080p SDR con audio indipendente; stop/pausa/ripresa; cambio camera attivo; uscita/rientro; terminazione forzata e rilettura della ripresa. Se disponibile, un test di due camere. Misurare PTS, drift A/V, buchi audio e memoria su due telefoni.
2. **Rendering:** due clip, titolo, crop e una transizione con audio; usare la stessa descrizione dell'edit per anteprima ed export. Verificare frame ai bordi, rotazione, durata e assenza di audio duplicato o mancante.
3. **ML:** una fixture parlata breve e una più lunga con Qwen e VoiceFixer. Registrare modello/backend, tempo, picco di memoria, qualità, cancellazione e output. Se VoiceFixer non si converte nel tempo disponibile, documentare il blocco e rinviarlo: il prototipo non va prolungato indefinitamente.

Uscita dalla fase: scegliere il percorso di acquisizione, l'estensione necessaria a Media3, i dispositivi di riferimento e le funzioni ammesse nella roadmap. Se fallisce anche la baseline singola SDR o il render semplice, correggere l'architettura e ricalcolare le stime prima di estendere l'app.

### Fase 1: perimetro preciso della prima release

Inclusi:

- Camera singola, 1080p/30 SDR, microfono integrato; scelta frontale/posteriore da fermo. Cambio durante il take solo se il prototipo è già accettato sui dispositivi dichiarati.
- Registrazione, pausa/ripresa, append, originali immutabili, riapertura del progetto e recupero delle riprese completate. Qualsiasi coda non recuperabile dopo crash va misurata e dichiarata.
- Progetti, rinomina, miniature, duplicazione, cestino reversibile e original recordings.
- Import di video/foto e audio nei formati inizialmente validati; rifiuto esplicito degli altri.
- Timeline sequenziale: selezione, split, trim, eliminazione, riordino, bookmark, miniature e waveform essenziali.
- Undo/Redo durevole con il modello che permetterà tracce indipendenti; nuove registrazioni protette dallo storico.
- Volume, gain/fade di base; crop, mirror e un titolo semplice con font distribuibile.
- Export MP4 H.264/AAC a 1080p/30 con condivisione, progress, cancellazione e controllo spazio.
- Backup e ripristino Android. Import iOS solo per un sottoinsieme riconosciuto e verificato, con rilevazione dei progetti non supportati.

Rimandati: multitraccia avanzata, transizioni complete, speed, catena completa di effetti, ML pesante, doppia camera, HDR e compatibilità iOS completa. La fase 1 è una prima release con meno funzioni dell'originale: non conclude il porting.

### Fasi 2 e 3: ordine delle aggiunte

Ordine preferito per l'editor: tracce indipendenti e placement → transizioni e audio → speed/pauses/freeze → titoli e immagini estesi → LUT/effetti → animazioni → RNNoise → stabilizzazione offline. Ogni aggiunta deve passare anche attraverso export, persistenza, duplicazione e Undo/Redo.

Ordine preferito per le funzioni avanzate, da rivedere con i benchmark: sottotitoli Qwen → tracking/face follow e Auto Background → capacità 4K/HDR e microfoni esterni → doppia camera → DeepFilterNet/LavaSR → VoiceFixer → Night fix temporale. Questo è un ordine di integrazione, non un ordine per scoprire i rischi: VoiceFixer e doppia camera vengono già sondati nella fase 0.

La compatibilità iOS avanza per livelli: prima struttura e media ordinari, poi effetti/font/maschere e storico, infine progetti con modelli e formati particolari. Ogni livello deve dichiarare quali progetti può leggere, modificare ed esportare; preservare un effetto sconosciuto nel file non autorizza a ignorarlo nel render.

## 6. Regole di accettazione

- Prima di dichiarare pronta la registrazione: take di 30–60 minuti, confronto audio/video all'inizio e alla fine, rotazione/mirror controllati sulla preview e nel file, memoria stabile e nessuna perdita non segnalata. Le prove dei cambi microfono distinguono una discontinuità prevista da un errore.
- Prima di dichiarare pronto un edit: stessa durata e stesso fotogramma sorgente in preview/export ai punti critici, audio corretto ai confini, stato identico dopo riapertura e Undo/Redo.
- Prima di dichiarare pronto un filtro ML: fixture di riferimento, qualità ascoltata, sample count e delay verificati, RAM/tempo misurati, cancellazione e ripresa provate. Un caricamento del modello riuscito non basta.
- Prima di dichiarare pronto uno scambio progetto: test Android–Android e iOS–Android del livello supportato, compreso Redo, censura, font e risorse non visibili nell'edit corrente.
- Prima di dichiarare pronta una funzione camera: interazione pubblica, telefono fisico e file salvato sullo stesso codice finale, secondo il principio di [CAMERA-QA.md](CAMERA-QA.md).
- Prima del rilascio ampio: terminazione forzata durante save/import/export, poco spazio, background/foreground e progetti lunghi. Nessuna correzione deve sovrascrivere gli originali.

L'impegno iniziale realistico è consegnare un'app base affidabile e misurare presto le funzioni difficili. L'impegno sulla parità avanzata viene assunto funzione per funzione dopo le prove; Apple Log nativo, area speciale del sensore selfie e profondità Cinematic rimangono decisioni separate.
