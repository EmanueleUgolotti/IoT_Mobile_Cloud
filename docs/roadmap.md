# Roadmap del progetto

Elenco dei pezzi da costruire, in ordine. Segniamo con ✅ quelli completati
alla fine di ogni task. Per ogni pezzo, i file/cartelle coinvolti — per i
task non ancora fatti, è il percorso dove finiranno i file quando li
creeremo.

---

## Fondamenta

- ✅ **Struttura del repository** — cartelle, README, .gitignore
  `README.md`, `.gitignore`, struttura completa delle cartelle
- ✅ **Schema dati** — come si parlano i pezzi tra loro (ESP32→ESP32,
  ESP32→Mobile/Cloud), incluse le due modalità: `reference` e `captured`
  `docs/architecture/network-design.md`

## Backend Cloud

- ✅ **Centralino di ricezione** (`/cloud/ingest`) — riceve le acquisizioni,
  controlla che siano valide, le salva. Gestisce sia `reference` che
  `captured`. Testato e funzionante.
  `backend/api/main.py`, `backend/api/models.py`, `backend/api/db.py`,
  `backend/api/requirements.txt`, `backend/api/Dockerfile`,
  `backend/database/init.sql`, `docker/docker-compose.yml`
  (sintesi: `docs/architecture/backend-api-summary.md`)
- ✅ **Servizio di inferenza ML** — non un nuovo servizio separato: il
  backend `api` chiama internamente il servizio `robustness` (che ha
  già il modello VGG16 caricato in memoria, usato anche per gli
  attacchi adversarial), tramite il suo endpoint `/robustness/predict`
  già esistente. Scelta deliberata per evitare di caricare TensorFlow
  una seconda volta in un container separato (costoso in RAM - vedi
  problemi di memoria affrontati mesi fa). Se la classificazione
  fallisce (servizio irraggiungibile), la richiesta resta comunque
  salvata senza risultato - non fa mai fallire la risposta al Gateway.
  **Testato e funzionante**: `Hf73.jpg` classificato correttamente come
  "gun" con confidenza 99.97%, in meno di un secondo.
  → `backend/api/main.py` (funzione `classify_and_store`)
- ✅ **Collegamento inferenza → database** — tre nuove colonne
  (`predicted_label`, `probabilities` JSONB, `classified_at`),
  aggiornate con una query separata dall'insert iniziale.
  → `backend/api/db.py` (funzione `update_classification`),
  `backend/database/init.sql`
- ✅ **Endpoint di lettura risultati** (`GET /cloud/results`) —
  restituisce le ultime acquisizioni già classificate, più recenti
  prima. **Testato e funzionante.**
  → `backend/api/main.py` (funzione `get_results`),
  `backend/api/db.py` (funzione `get_recent_results`)

## App Mobile — Cronologia Cloud
 
- ⬜ **Visualizzazione risultati Cloud nell'app** — l'app chiama il nuovo
  endpoint di lettura e mostra le classificazioni fatte dal cloud,
  idealmente integrate nella stessa Cronologia già esistente (fonte
  "Cloud", accanto a "Mobile" e "Foto locale"), non una sezione separata.
  Riusa il pattern già collaudato con `RobustnessApiClient` per le
  chiamate HTTP dall'app verso un servizio backend.
  → `android/app/src/main/java/com/manu/myapp/` (nuovo client HTTP o
  estensione di uno esistente, integrazione con `HistoryFragment`/`HistoryDbHelper`)

## App Mobile

- ✅ **Ricezione dati dal gateway** — server HTTP embedded (NanoHTTPD) che
  espone `/mobile/ingest`, stesso schema JSON del backend cloud. Validato
  con richiesta di test (via `adb forward` + curl). Testato e funzionante.
  `android/app/src/main/java/com/manu/myapp/IngestServer.java`,
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica),
  `android/app/build.gradle` (dipendenza NanoHTTPD),
  `android/app/src/main/AndroidManifest.xml` (permesso INTERNET)
- ✅ **Preprocessing dell'immagine + Inferenza locale con TensorFlow Lite** —
  modello VGG16 convertito in `.tflite`, integrato nell'app: decodifica
  immagine ricevuta (modalità `captured`), preprocessing identico al
  training (conversione RGB→BGR + sottrazione media ImageNet, NON /255),
  esecuzione modello, log del risultato. Verificato end-to-end su
  dispositivo reale (server → decodifica → preprocessing → inferenza),
  risultato coerente con i test Python. Richiesto downgrade di
  Gradle/AGP (8.9 / 8.7) per conflitti di manifest, e libreria
  TensorFlow Lite alla versione 2.17.0 (le precedenti non riconoscevano
  l'opcode FULLY_CONNECTED v12 usato dal modello).
  `android/app/src/main/java/com/manu/myapp/Classifier.java`,
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica),
  `android/app/src/main/assets/bpa_vgg16.tflite`,
  `android/app/build.gradle.kts` (dipendenza TensorFlow Lite 2.17.0,
  android.useAndroidX/enableJetifier in gradle.properties),
  `models/bpa_vgg16.tflite` (sorgente del modello),
  `experiments/convert_to_tflite.py`, `experiments/test_tflite_inference.py`,
  `experiments/test_keras_inference.py`, `experiments/requirements.txt`,
  `experiments/make_captured_test_request.py` (script di test end-to-end)
- ✅ **Struttura di navigazione app** — Bottom Navigation con 4 schermate
  (Home, Cronologia, Test manuale, Profilo), sostituendo i Fragment demo
  del template. Solo scheletro navigabile, contenuto placeholder — la
  logica funzionante di ciascuna schermata è nei task seguenti.
  `android/app/src/main/res/navigation/nav_graph.xml`,
  `android/app/src/main/res/menu/bottom_nav_menu.xml`,
  `android/app/src/main/res/layout/activity_main.xml`,
  `android/app/src/main/res/layout/fragment_{home,history,manual_test,profile}.xml`,
  `android/app/src/main/java/com/manu/myapp/ui/{Home,History,ManualTest,Profile}Fragment.java`,
  `android/app/src/main/res/values/strings.xml`
- ✅ **Home: stato server in tempo reale + ultimo risultato** — la Home
  osserva uno stato condiviso (`AppState`, basato su LiveData) aggiornato
  da `MainActivity` ogni volta che il server cambia stato o arriva una
  classificazione: mostra porta/stato server, classe predetta, confidenza
  con barra, fonte e orario. Toccando il riquadro si apre una schermata
  di dettaglio con l'immagine analizzata a schermo intero e un grafico a
  barre con la probabilità di ciascuna delle 6 classi. Testato end-to-end
  su dispositivo reale.
  `android/app/src/main/java/com/manu/myapp/AppState.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/ResultDetailActivity.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica),
  `android/app/src/main/java/com/manu/myapp/ui/HomeFragment.java` (modifica),
  `android/app/src/main/res/layout/fragment_home.xml` (modifica),
  `android/app/src/main/res/layout/activity_result_detail.xml` (nuovo),
  `android/app/src/main/res/layout/item_probability_bar.xml` (nuovo),
  `android/app/src/main/AndroidManifest.xml` (registrazione ResultDetailActivity),
  `android/app/src/main/res/values/strings.xml` (modifica)
- ✅ **Cronologia: lista classificazioni passate** — persistenza reale
  (sopravvive alla chiusura dell'app) tramite database SQLite locale
  nativo (nessuna libreria esterna come Room, per non riesporsi a
  conflitti di dipendenze). Immagini salvate come file nello storage
  interno, il database tiene solo i metadati e il percorso. Lista con
  miniatura, nome file, classe, badge fonte, orario, confidenza; il
  tocco su una riga apre lo stesso dettaglio (immagine + grafico
  probabilità) già usato dalla Home, reso riutilizzabile per entrambi
  i casi. Testato: i dati sopravvivono alla chiusura completa dell'app.
  `android/app/src/main/java/com/manu/myapp/HistoryItem.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/HistoryDbHelper.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/ui/HistoryAdapter.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/ui/HistoryFragment.java` (modifica),
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica,
  salvataggio immagine su file + insert DB ad ogni classificazione),
  `android/app/src/main/java/com/manu/myapp/ResultDetailActivity.java`
  (modifica, ora accetta dati anche via Intent extras, non solo da AppState),
  `android/app/src/main/res/layout/item_history_row.xml` (nuovo),
  `android/app/src/main/res/layout/fragment_history.xml` (modifica),
  `android/app/src/main/res/values/strings.xml` (modifica)
- ✅ **Test manuale: scatta/scegli foto e classifica subito** — due modi
  per ottenere un'immagine: Photo Picker di sistema (galleria, nessun
  permesso richiesto) o app fotocamera (via intent, nessuna gestione
  permessi lato nostro). Nome file reale (assegnato dal sistema) per le
  foto da galleria; nome generato in formato Android per le foto appena
  scattate. Classificazione con lo stesso `Classifier` condiviso (via
  `AppState`), salvataggio in Cronologia con fonte "Foto locale"
  (distinta da "Mobile" per le acquisizioni dal gateway).
  `android/app/src/main/java/com/manu/myapp/ui/ManualTestFragment.java`   (nuovo contenuto), 
  `android/app/src/main/res/layout/fragment_manual_test.xml`  (modifica), 
  `android/app/src/main/java/com/manu/myapp/ImageStorage.java`   (nuovo, utility condivisa di salvataggio immagini, estratta da MainActivity),
  `android/app/src/main/java/com/manu/myapp/AppState.java` (modifica, riferimento condiviso al classificatore),
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica)
- ✅ **Correzione OutOfMemoryError con immagini ad alta risoluzione** —
  immagini sorgente molto grandi (es. foto originali del dataset)
  causavano crash sia in fase di classificazione (ridimensionamento
  bicubico che duplicava l'immagine intera in un array) sia in fase di
  visualizzazione del dettaglio. Risolto limitando le dimensioni massime
  (1024px sul lato più lungo) subito dopo la decodifica di qualunque
  immagine in ingresso, prima di ogni elaborazione successiva.
  `android/app/src/main/java/com/manu/myapp/ImageUtils.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/Classifier.java` (modifica,
  pre-ridimensionamento efficiente prima del bicubico),
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica),
  `android/app/src/main/java/com/manu/myapp/ui/ManualTestFragment.java` (modifica)
- ✅ **Eliminazione voci dalla Cronologia** — tenendo premuto su una riga,
  con conferma, si elimina sia il record dal database sia il file
  immagine associato.
  `android/app/src/main/java/com/manu/myapp/HistoryDbHelper.java` (modifica,  metodo `delete`), 
  `android/app/src/main/java/com/manu/myapp/ui/HistoryAdapter.java`   (modifica, gesto tieni-premuto), 
  `android/app/src/main/java/com/manu/myapp/ui/HistoryFragment.java` (modifica, dialogo di conferma + cancellazione file),
  `android/app/src/main/res/values/strings.xml` (modifica)
- ✅ **Profilo utente locale** — nome utente salvato tramite
  `SharedPreferences` (nessuna autenticazione vera), gestito da una
  utility condivisa (`ProfileManager`) invece che direttamente nel
  Fragment. Collegato al Test Manuale: le classificazioni fatte con un
  profilo impostato mostrano il nome nel badge fonte della Cronologia
  (es. "Foto locale — Manu"), invece del generico "Foto locale".
  `android/app/src/main/java/com/manu/myapp/ProfileManager.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/ui/ProfileFragment.java` (nuovo contenuto), 
  `android/app/src/main/res/layout/fragment_profile.xml` (modifica), 
  `android/app/src/main/java/com/manu/myapp/ui/ManualTestFragment.java`(modifica), 
  `android/app/src/main/res/values/strings.xml` (modifica)
- ✅ **Impostazioni** — schermata dedicata (`SettingsActivity`, raggiungibile
  dal menu della Toolbar) con tre controlli: porta del server (salvata,
  effettiva al prossimo riavvio dell'app — il server non si riavvia "a
  caldo"), tema chiaro/scuro/sistema (applicato subito, tramite
  `AppCompatDelegate`), lingua italiano/inglese (meccanismo di cambio
  attivato subito, tramite `AppCompatDelegate.setApplicationLocales`,
  ma le traduzioni inglesi vere arrivano nel task successivo). Tema e
  lingua applicati il prima possibile all'avvio tramite una classe
  `Application` dedicata (`BpaApplication`), non solo dentro una singola
  Activity, così ogni schermata li riceve fin dal primo frame.
  `android/app/src/main/java/com/manu/myapp/SettingsManager.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/BpaApplication.java` (nuovo),
  `android/app/src/main/java/com/manu/myapp/SettingsActivity.java` (nuovo),
  `android/app/src/main/res/layout/activity_settings.xml` (nuovo),
  `android/app/src/main/res/menu/menu_main.xml` (nuovo/verificato),
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica,
  porta letta da SettingsManager, menu Impostazioni collegato),
  `android/app/src/main/AndroidManifest.xml` (modifica, registrazione  BpaApplication e SettingsActivity), `android/app/src/main/res/values/strings.xml`  (modifica)
- ✅ **Localizzazione IT/EN** — file `values-en/strings.xml` completo,
  48 stringhe tradotte (verificate: stesse chiavi presenti in entrambi i
  file, nessuna mancante). Il meccanismo di cambio lingua (da task
  Impostazioni) ora mostra davvero i testi inglesi quando selezionato.
  `android/app/src/main/res/values-en/strings.xml` (nuovo)
- ✅ **Profilo collegato alla Cronologia** — le classificazioni fatte dal
  Test Manuale mostrano il nome profilo nel badge fonte (es. "Foto
  locale — Manu"), invece del generico "Foto locale".
  `android/app/src/main/java/com/manu/myapp/ProfileManager.java` (nuovo,  utility condivisa), 
  `android/app/src/main/java/com/manu/myapp/ui/ProfileFragment.java`  (modifica), 
  `android/app/src/main/java/com/manu/myapp/ui/ManualTestFragment.java`(modifica)
- ✅ **Note libere sui risultati + ricerca nella Cronologia** — ogni
  classificazione salvata può ricevere una nota di testo libero
  (modificabile dalla schermata Dettaglio); la Cronologia ha una casella
  di ricerca che filtra in tempo reale per nome file o per contenuto
  della nota. Database aggiornato da v1 a v2 con `ALTER TABLE` (non
  ricreato), per non perdere le classificazioni già salvate.
  `android/app/src/main/java/com/manu/myapp/HistoryDbHelper.java`  (modifica: colonna nota, `updateNote`, `search`, `insert` ora restituisce l'id), 
  `android/app/src/main/java/com/manu/myapp/HistoryItem.java` (modifica: campo nota), 
  `android/app/src/main/java/com/manu/myapp/AppState.java` (modifica: id nel LastResult), 
  `android/app/src/main/java/com/manu/myapp/MainActivity.java` (modifica), 
  `android/app/src/main/java/com/manu/myapp/ResultDetailActivity.java` (modifica: sezione nota, chiusura tastiera al salvataggio),
  `android/app/src/main/java/com/manu/myapp/ui/ManualTestFragment.java`  (modifica), 
  `android/app/src/main/java/com/manu/myapp/ui/HistoryAdapter.java`  (modifica), 
  `android/app/src/main/java/com/manu/myapp/ui/HistoryFragment.java` (modifica: casella di ricerca, chiusura tastiera),
  `android/app/src/main/res/layout/activity_result_detail.xml` (modifica),
  `android/app/src/main/res/layout/fragment_history.xml` (modifica),
  `android/app/src/main/res/values/strings.xml` e `values-en/strings.xml`(modifica, mantenuti allineati: 53 chiavi in entrambi)

- ⬜ **Personalizzazione grafica** (colori, font, tema visivo — rimandata
  deliberatamente a quando le funzionalità sono stabili, per non dover
  ritoccare lo stile a ogni nuova schermata aggiunta)
  → `android/app/src/main/res/values/colors.xml`,
  `android/app/src/main/res/values/themes.xml`

## ESP32 #2 — Gateway

- ✅ **Ricezione pacchetti da ESP32 #1** (via ESP-NOW) — progetto ESP-IDF
  (v5.5.1, target esp32s3) inizializzato: WiFi in modalità stazione
  (necessaria per attivare il radio ESP-NOW, nessuna connessione a un
  vero access point richiesta), ESP-NOW come ricevitore, struttura del
  pacchetto coerente con `network-design.md`, validazione CRC (scarta
  pacchetti corrotti), log su seriale. Flashato e verificato su
  hardware reale: si avvia correttamente, stampa il proprio indirizzo
  MAC (`DC:B4:D9:09:FB:20`, da usare come peer nell'Acquisition Node).
  Non ancora incluso in questo task: inoltro HTTP, logica di
  offloading, coda di retry (task successivi).
  → `esp32/gateway/CMakeLists.txt`, `esp32/gateway/main/CMakeLists.txt`,
  `esp32/gateway/main/main.c`
- ✅ **Logica di offloading + Invio verso Cloud/Mobile** — priorità al
  Cloud (più potenza di calcolo), fallback su Mobile se non
  raggiungibile (controllo rapido su `/health`). Configurazione (WiFi,
  indirizzi backend, API key) tramite `idf.py menuconfig`, nessuna
  credenziale scritta nel codice. Payload JSON coerente con
  `network-design.md`, inoltrato via `esp_http_client` verso
  `/cloud/ingest` o `/mobile/ingest`.
  **Verificato end-to-end su hardware reale**, incluso il download
  dell'immagine vera dal dataset condiviso e la classificazione visibile
  in Cronologia sull'app — vedi decisions-log per il lungo percorso di
  debug che ha portato a questo risultato (7 cause distinte trovate e
  risolte, punti 35-41).
  → `esp32/gateway/main/main.c`, `esp32/gateway/main/Kconfig.projbuild`,
  `backend/api/main.py` (nuovo endpoint `/dataset/image`)
- ✅ **Coda locale con retry** — coda FreeRTOS (capacità 10) + task in
  background che ogni 30 secondi riprova l'inoltro dei pacchetti falliti,
  rifacendo da capo la decisione di offloading (non fissata sulla
  destinazione già fallita). Limite noto e accettato: la coda vive solo
  in RAM, persa a un riavvio del Gateway.
  → `esp32/gateway/main/main.c`

## ESP32 #1 — Acquisition Node

- ✅ **Modalità `reference`** — sceglie un'immagine dal dataset e manda solo
  il riferimento. **Testato end-to-end** insieme al Gateway.
  → `esp32/acquisition-node/`
- ✅ **Fotocamera + display** — OV2640 e LCD della scheda ESP32-S3-EYE
  integrati nel firmware, riusando il codice del progetto di prova
  ufficiale `esp32/display_camera/` (verificato isolatamente prima di
  integrarlo). Anteprima video dal vivo, fluida. Pulsante BOOT: pressione
  breve scatta una foto vera (per ora solo loggata), pressione lunga
  accende/spegne la modalità automatica di invio riferimenti - decidibile
  a runtime, senza riflashare. **Testato e funzionante.**
  → `esp32/acquisition-node/main.c`, `esp32/display_camera/` (progetto di
  prova, mantenuto come riferimento/prova che l'hardware funziona
  isolatamente - vedi decisions-log)
- ⬜ **Frammentazione ESP-NOW + modalità `captured`** — spezzare una foto
  vera in più pacchetti ESP-NOW (troppo grande per un pacchetto solo),
  ricomponibile lato Gateway, poi inoltrabile come modalità `captured`
  (già gestita dal backend). Include la notifica di un tentativo fallito
  (pezzi persi) al backend, cosi' l'utente sa che lo scatto va rifatto.
  → `esp32/acquisition-node/`, `esp32/gateway/`

## Robustezza / gestione errori (trasversale a più pezzi)

- ✅ **Discrepanza di precisione numerica Python vs Android** — stessa
  classe predetta, confidenza leggermente diversa tra i due ambienti
  (decodifica JPEG e algoritmo di ridimensionamento diversi). Applicato
  resize bicubico anche lato Android per ridurre la discrepanza; causa
  residua (decoder JPEG nativo) documentata come limite noto, non
  bloccante per il funzionamento del sistema.
  `android/app/src/main/java/com/manu/myapp/Classifier.java` (resize bicubico)
  (dettagli: `docs/architecture/inference-precision-notes.md`)
- ✅ **Riferimento immagine inesistente** — gestito lato backend (risponde
  con errore chiaro, non si pianta)
  `backend/api/main.py` (funzione `_handle_reference`)
- ⬜ **WiFi assente/interrotto** — coda locale con retry sul gateway
  → `esp32/gateway/`
- ⬜ **Backend cloud irraggiungibile** — timeout + retry
  → `esp32/gateway/`
- ⬜ **App mobile che non risponde** — fallback automatico su cloud
  → `esp32/gateway/`
- ⬜ **Autenticazione** — meccanismo già scritto nel backend, da testare
  attivo/disattivo in demo
  `backend/api/main.py` (funzione `check_auth`), `docker/docker-compose.yml`
  (variabili `AUTH_ENABLED` e `API_KEY`) — lato gateway ancora da fare,
  → `esp32/gateway/`

## Adversarial Robustness (dal tirocinio)

- ⬜ **Decisione dataset**: bloodstain o CIFAR-10 — da chiudere quando si
  arriva a questo modulo
  → `backend/robustness/`, `experiments/`
- 🔶 **App mobile come "telecomando" per gli attacchi adversarial**
  (in corso — backend pronto e verificato, endpoint e app non ancora fatti) —
  l'app NON esegue gli attacchi (richiederebbero riscrivere in Java
  codice numerico intensivo, es. NumPy/PyTorch, con alto rischio di bug
  sottili — vedi decisions-log). L'utente sceglie un'immagine e un
  attacco dall'app, l'app manda una richiesta HTTP al backend, il
  backend esegue l'attacco riusando il codice Python del tirocinio quasi
  invariato, e restituisce immagine perturbata + nuove predizioni.
  → `backend/robustness/`, `android/app/src/main/java/com/manu/myapp/`
  (nuova funzionalità UI)
  - ✅ **Sotto-task: preparazione backend (Boundary)** — script
    `run_hsj_zoo_boundary_vgg16.py`, `pipeline_predict.py`,
    `pipeline_predict_vgg16.py` copiati e adattati (percorsi modello
    unificati in `models/`, import ML reso opzionale). Logica di
    attacco estratta in una funzione riutilizzabile `run_art_attack()`,
    pensata per essere chiamata sia da CLI sia da un futuro endpoint.
    Verificato end-to-end sia nel sandbox di sviluppo sia sul PC reale
    dell'utente: caricamento modello, attacco Boundary (tramite ART
    `BlackBoxClassifier`, nessuna dipendenza da PyTorch necessaria per
    questo attacco), predizione prima/dopo, salvataggio immagine.
    Corretto un problema di memoria: l'attacco lavorava sull'immagine a
    risoluzione originale invece che a 224×224 (quella vista dal
    modello), causando avvisi di allocazione eccessiva e un blocco del
    PC dell'utente con immagini ad alta risoluzione — risolto
    ridimensionando prima dell'attacco.
    `backend/robustness/pipeline_predict.py`,
    `backend/robustness/pipeline_predict_vgg16.py`,
    `backend/robustness/run_hsj_zoo_boundary_vgg16.py`,
    `backend/robustness/requirements.txt`, `models/best_model.keras`,
    `models/class_indices.json` (percorsi unificati)
  - ✅ **Sotto-task: preparazione backend (SimBA)** — verificato sul PC
    reale dell'utente, con successo, dopo un percorso di debug lungo
    (vedi punto 26-30 del decisions-log): TensorFlow e PyTorch non
    possono coesistere nello stesso processo dentro Docker (crash a
    basso livello, verificato empiricamente) — risolto separando SimBA
    in un servizio Docker indipendente (`backend/robustness_simba/`,
    solo PyTorch), che interroga il modello VGG16 via HTTP invece di
    caricarlo in locale.
    → `backend/robustness_simba/` (intero servizio nuovo)
  - ✅ **Sotto-task: endpoint HTTP** (`POST /robustness/attack`) —
    completato e verificato end-to-end per entrambi gli attacchi
    (Boundary via ART/TensorFlow, SimBA via il servizio separato).
    Risolti nel percorso: un bug di ordine di avvio dei container
    (dipendenza invertita nel docker-compose, causava una corsa
    all'avvio), un endpoint che bloccava l'intero servizio durante un
    attacco lungo (corretto eseguendo gli attacchi in un thread separato,
    non sul ciclo principale del servizio), e un errore di
    serializzazione (numpy.float32 non convertibile automaticamente in
    JSON).
    → `backend/robustness/main.py`, `backend/robustness_simba/main.py`,
    `docker/docker-compose.yml` (nuovo servizio + healthcheck)
  - ✅ **Sotto-task: azione nell'app mobile** — completato e verificato
    sul dispositivo dell'utente. Nuova schermata "Adversarial" (quinta
    scheda nella barra di navigazione): sceglie un'immagine (galleria o
    Cronologia classificazioni), sceglie l'attacco (Boundary/SimBA),
    l'attacco gira in un Foreground Service indipendente dalla
    schermata — l'utente puo' cambiare scheda o uscire del tutto
    dall'app, con una notifica persistente durante l'esecuzione e una
    notifica finale con l'esito al termine. Cronologia dedicata degli
    attacchi (database separato da quello delle classificazioni, dato
    lo schema diverso: due immagini e due predizioni per voce), con le
    due immagini (originale/avversaria) toccabili per essere viste
    ingrandite. Corretto un limite di dimensione immagine mancante
    (stesso problema di OOM gia' risolto altrove, non ereditato
    automaticamente dalla nuova schermata) e un timeout lato app troppo
    breve (5 minuti) rispetto ai tempi reali osservati (fino a 8 minuti
    per Boundary senza GPU), alzato a 15 minuti.
    → `android/app/src/main/java/com/manu/myapp/RobustnessApiClient.java`,
    `android/app/src/main/java/com/manu/myapp/SettingsManager.java`
    (indirizzo backend configurabile),
    `android/app/src/main/java/com/manu/myapp/AdversarialAttackService.java`
    (nuovo, Foreground Service),
    `android/app/src/main/java/com/manu/myapp/AdversarialHistoryItem.java`,
    `android/app/src/main/java/com/manu/myapp/AdversarialHistoryDbHelper.java`,
    `android/app/src/main/java/com/manu/myapp/ImageViewerActivity.java`,
    `android/app/src/main/java/com/manu/myapp/ImageUtils.java` (riusato),
    `android/app/src/main/java/com/manu/myapp/ui/AdversarialTestFragment.java`,
    `android/app/src/main/java/com/manu/myapp/BpaApplication.java`
    (canale di notifica), `android/app/src/main/AndroidManifest.xml`
    (permessi Foreground Service/notifiche, registrazione Service e
    Activity), `android/app/src/main/res/xml/network_security_config.xml`
    (traffico HTTP non cifrato, necessario per il backend locale di
    sviluppo), `android/app/src/main/res/layout/fragment_adversarial_test.xml`,
    `android/app/src/main/res/layout/activity_image_viewer.xml`,
    `android/app/src/main/res/values/strings.xml` e `values-en/strings.xml`
    (94 chiavi allineate)`
- ⬜ **Integrazione componente di robustness nel backend**
  → `backend/robustness/`

## Estensioni opzionali (solo se avanza tempo)

- ⬜ Frammentazione ESP-NOW per la modalità `captured`
  → `esp32/acquisition-node/`, `esp32/gateway/`
- ⬜ MQTT come alternativa/affiancamento a HTTP
  → `backend/api/`, `docker/docker-compose.yml`, `esp32/gateway/`
- ⬜ Docker Swarm come estensione del deployment
  → `docker/`

---

*Nota: questo file va aggiornato a mano man mano che chiudiamo i task, non
contiene dettagli tecnici approfonditi — quelli restano nei documenti
dedicati in `docs/architecture/`.*