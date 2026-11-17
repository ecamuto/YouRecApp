# Android 0.3: interruzione della ripresa e riapertura

17 novembre 2026. Samsung SM-A256B, Android 16/API 36. Stesso codice Android e
stesso APK delle prove di [cambio camera](ANDROID-SWITCH.md), SHA-256
`57c3f4f25f61c18e7c135a56c0d9f6f9b5f24960ea31a6071c6e3b4135e1f40f`.
Non sono stati modificati acquisizione, muxer o interfaccia in questa verifica.

**Verificati spegnimento schermo e terminazione forzata durante un take. Il
recupero della ripresa interrotta rimane da implementare.** La prova è stata scelta
come attività residua più breve della fase 0: qualifica due comportamenti e
conferma una limitazione del formato attuale, senza chiudere tutta la fase.

## Risultati fisici

| Caso | Risultato osservato |
| --- | --- |
| Spegnimento schermo durante registrazione | Stop automatico e pubblicazione di un file leggibile, prima di riaccendere lo schermo |
| Riaccensione e ritorno all'app | Camera nuovamente pronta; la ripresa salvata compare nella libreria |
| `am force-stop` mentre la UI mostra Registrazione in corso | Processo terminato senza passare da Home/Back/Ferma; nessun nuovo file pubblicato in completed |
| Riapertura dopo force-stop | File interrotto conservato in pending; avviso visibile in libreria; riproduzione della precedente ripresa funzionante |
| Nuova ripresa dopo riapertura | Avvio, stop e salvataggio riusciti; file decodificabile |
| Conservazione | Tutte le 28 riprese completate e il file pending preesistenti mantengono il proprio SHA-256; preferenze globali di rotazione invariate |

La prova usa i controlli pubblici per registrare, fermare, aprire la libreria e
riprodurre. `KEYCODE_SLEEP` simula lo spegnimento schermo e la policy raccolta
conferma `SCREEN_STATE_OFF` / `INTERACTIVE_STATE_SLEEP`. Il telefono non aveva
un blocco sicuro: PIN/biometria e riavvio del dispositivo non sono coperti.
La terminazione diretta è verificata con assenza del PID; non simula una chiamata,
un crash nativo o un esaurimento dello spazio.

I due file finalizzati sono H.264 1920×1080 SDR BT.709 limited, senza B-frame,
con AAC stereo 48 kHz. Entrambi superano decodifica integrale e PTS strettamente
crescenti:

| File | Durata video / audio | Passo video massimo | Passo AAC massimo |
| --- | ---: | ---: | ---: |
| `9ed6f5df-5b12-44b2-b32b-258f1c19ecd5.mp4` — schermo spento | 7,271 / 7,296 s | 37,178 ms | 21,334 ms |
| `15c18e19-f034-4087-88b8-84eb6ed48eb8.mp4` — nuova ripresa dopo kill | 6,592 / 6,592 s | 38,945 ms | 21,334 ms |

Preview reale e riproduzione del primo file mostrano la stessa scena, dritta e
con campo completo. Queste durate brevi non misurano drift, lip-sync o continuità
del contenuto audio con una sorgente di riferimento.

## Limite di recupero confermato

Il file `7b234cc6-2985-4a41-9fd5-77e0ae70adec.mp4` contiene **31.670.727 byte**, ma
FFprobe lo rifiuta con `moov atom not found`. La conservazione dei byte e l'avviso
in libreria funzionano; la ripresa non è riproducibile dal percorso attuale.
Non si può promettere la perdita della sola coda: in questa prova l'intero take
in corso resta indisponibile, mentre quelli già finalizzati sono intatti.
Non è stata tentata una riparazione esterna né dimostrata l'impossibilità di
estrarre manualmente singoli campioni.

Per qualificare il recupero serve un successivo prototipo con segmenti finalizzati
o MP4 frammentato, ricevuta durevole e riconciliazione alla riapertura. Andrà
interrotto in più punti, verificando campioni effettivamente recuperati e coda
persa. Anche chiamate, contesa/routing audio, poco spazio e kill durante save
restano aperti. Questa sessione non implementa quei comportamenti.

## Evidenze e ripetibilità

Materiale locale in `.benchmarks/android/interruptions/`, escluso da Git:

- `probe.py`, `events.json`, screenshot/XML: sequenza fisica e messaggi pubblici.
- `inventory-before.json`, `inventory-locked.json`, `inventory-killed.json`,
  `inventory-after.json`: conservazione e collocazione dei file durante la prova.
- `verified-prior-backups.json`, `settings-before.json`, `settings-after.json`:
  backup delle riprese preesistenti verificati prima della prova e preferenze.
- `media/`: i tre file prodotti, compreso quello interrotto.
- `lock-timing.json`, `reopened-timing.json`, `interrupted-ffprobe.json`:
  controlli sui file reali, compreso il fallimento previsto del file interrotto.
- `04-asleep-policy.txt`, `07-process-after-kill.txt`, `force-stop.json`,
  `capture-log.txt`, `result.json`: stato schermo, processo ed esito delle verifiche.
- `tested-android-hashes.json`: corrispondenza dei sorgenti Android/APK con le
  precedenti prove 0.3. Nessuna nuova build necessaria per questi test esterni.

## Pulizia dei soli test

Su richiesta dell'utente, le copie dei test sul telefono sono state rimosse dopo
averne conservato le evidenze sul Mac. L'elenco esatto è in `cleanup-manifest.json`:
32 file (30 completati e 2 provvisori), per **2.507.592.226 byte**. Include sia
l'anomalia audio precedente sia il take interrotto: entrambi restano archiviati
localmente e non vengono trattati come risultati positivi.

Il nuovo [harness di pulizia](../android/tools/cleanup_captures.py) accetta solo
UUID MP4 sotto `files/capture-prototype/{completed,pending}` del package debug.
Richiede app già ferma, manifest esplicito legato al dispositivo e copia locale
con SHA-256 corrispondente per ogni target. Il default è una simulazione.
Con `--apply` crea prima il backup dell'intero contenitore privato, ne verifica
tutti i file regolari e ricontrolla che i dati siano invariati. Cancella soltanto
i nomi elencati e confronta subito inventario, checksum e percorsi rimanenti,
prima di riaprire l'app. Nessun comando ricorsivo, reset dei dati o disinstallazione.
I test Python verificano rifiuto di percorsi estranei, traversal, wildcard,
duplicati, device/package errati, evidenze mancanti e backup parziali o corrotti.

**Pulizia completata:** rimossi i 32 file elencati. I due altri file privati
dell'app e tutti i percorsi rimanenti sono invariati nel confronto immediato,
prima della riapertura. Audit e backup completo sono conservati in
`cleanup-applied/`; la simulazione precedente in `cleanup-dry-run/`.
Alla riapertura sono verificati libreria vuota, 0 completati / 0 pending e camera
nuovamente pronta, senza registrazioni attive. Preferenze globali di rotazione e
hash dell'APK installato sono invariati. Risultati e screenshot/XML finali:
`cleanup-result.json` e `cleanup-01-idle.*` / `cleanup-02-empty-library.*` /
`cleanup-03-final-camera.*`. I 9 test di sicurezza Python passano; build/JVM/lint
Android restano quelli della stessa build 0.3 già collaudata.
