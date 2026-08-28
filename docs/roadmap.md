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
- ⬜ **Servizio di inferenza ML** — la parte che guarda davvero dentro
  l'immagine e dice che tipo di macchia è (userà i modelli del tirocinio)
  → `backend/inference/`
- ⬜ **Collegamento inferenza → database** — salvare anche il risultato
  della classificazione, non solo i metadati della richiesta
  → `backend/inference/`, modifiche a `backend/database/init.sql`

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

- ⬜ **Ricezione pacchetti da ESP32 #1** (via ESP-NOW)
  → `esp32/gateway/`
- ⬜ **Logica di offloading** (decide se mandare a Mobile o Cloud)
  → `esp32/gateway/`
- ⬜ **Invio verso Cloud/Mobile** (via WiFi/HTTP) — si aggancia al backend
  già pronto
  → `esp32/gateway/`
- ⬜ **Coda locale con retry** (se il WiFi cade, non perde i dati, riprova)
  → `esp32/gateway/`

## ESP32 #1 — Acquisition Node

- ⬜ **Modalità `reference`** — sceglie un'immagine dal dataset e manda solo
  il riferimento (richiede solo un ESP32 base, hardware che già hai)
  → `esp32/acquisition-node/`
- ⬜ **Modalità `captured`** — scatta davvero una foto con la webcam e la
  manda (richiede l'ESP32 con fotocamera)
  → `esp32/acquisition-node/`

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