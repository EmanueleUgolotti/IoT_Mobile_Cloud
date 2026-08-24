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

- ⬜ **Ricezione dati dal gateway** (via WiFi/HTTP, quando il gateway decide
  di mandare al mobile invece che al cloud)
  → `android/`
- ⬜ **Preprocessing dell'immagine**
  → `android/`
- ⬜ **Inferenza locale con TensorFlow Lite**
  → `android/`, più il modello convertito in `models/`
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
