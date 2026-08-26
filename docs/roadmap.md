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

  il telefono ora ha un piccolo server integrato (IngestServer.java) che ascolta sulla porta 8080 esattamente come fa il backend cloud — riceve la stessa struttura di richiesta, la valida, e (per ora) la stampa nel log. Non guarda ancora dentro l'immagine.


- ✅ Preprocessing dell'immagine + Inferenza locale con TensorFlow Lite — modello VGG16 convertito in .tflite, integrato nell'app: decodifica immagine ricevuta (modalità captured), preprocessing identico al training (conversione RGB→BGR + sottrazione media ImageNet, NON /255), esecuzione modello, log del risultato. Verificato end-to-end su dispositivo reale (server → decodifica → preprocessing → inferenza), risultato coerente con i test Python. Richiesto downgrade di Gradle/AGP (8.9 / 8.7) per conflitti di manifest, e libreria TensorFlow Lite alla versione 2.17.0 (le precedenti non riconoscevano l'opcode FULLY_CONNECTED v12 usato dal modello). android/app/src/main/java/com/manu/myapp/Classifier.java, android/app/src/main/java/com/manu/myapp/MainActivity.java (modifica), android/app/src/main/assets/bpa_vgg16.tflite, android/app/build.gradle.kts (dipendenza TensorFlow Lite 2.17.0, android.useAndroidX/enableJetifier in gradle.properties), models/bpa_vgg16.tflite (sorgente del modello), experiments/convert_to_tflite.py, experiments/test_tflite_inference.py, experiments/test_keras_inference.py, experiments/requirements.txt, experiments/make_captured_test_request.py (script di test end-to-end)

- ✅ Struttura di navigazione app — Bottom Navigation con 4 schermate (Home, Cronologia, Test manuale, Profilo), sostituendo i Fragment demo del template. Solo scheletro navigabile, contenuto placeholder — la logica funzionante di ciascuna schermata è nei task seguenti. android/app/src/main/res/navigation/nav_graph.xml, android/app/src/main/res/menu/bottom_nav_menu.xml, android/app/src/main/res/layout/activity_main.xml, android/app/src/main/res/layout/fragment_{home,history,manual_test,profile}.xml, android/app/src/main/java/com/manu/myapp/ui/{Home,History,ManualTest,Profile}Fragment.java, android/app/src/main/res/values/strings.xml

- ⬜ Home: stato server in tempo reale + ultimo risultato (sostituire il placeholder statico con dati reali, aggiornati quando arriva un'acquisizione) → android/app/src/main/java/com/manu/myapp/ui/HomeFragment.java
- ⬜ Cronologia: lista classificazioni passate (con anteprima immagine, indicatore di confidenza, badge fonte Mobile/Cloud/Foto locale) → android/app/src/main/java/com/manu/myapp/ui/HistoryFragment.java
- ⬜ Test manuale: scatta/scegli foto e classifica subito → android/app/src/main/java/com/manu/myapp/ui/ManualTestFragment.java
- ⬜ Profilo utente locale (nome utente salvato sul telefono, non autenticazione vera) → android/app/src/main/java/com/manu/myapp/ui/ProfileFragment.java
- ⬜ Impostazioni (porta server, lingua IT/EN, tema chiaro/scuro) → android/ (nuova schermata/Activity dedicata)
- ⬜ Localizzazione IT/EN (da fare per ultima, quando i testi sono stabili) → android/app/src/main/res/values/strings.xml, android/app/src/main/res/values-en/strings.xml

- ⬜ **Visualizzazione del risultato** (che tipo di macchia ha riconosciuto)
  → `android/`

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
