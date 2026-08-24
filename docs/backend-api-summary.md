# Backend Cloud — Skeleton di Ingest (Task 3)

## Scopo

Questo componente implementa il punto di ingresso cloud del sistema: riceve le
acquisizioni inoltrate dal Gateway (ESP32 #2), le valida secondo il contratto
dati definito in `docs/architecture/network-design.md`, e le persiste su
database. È il primo anello della catena "Cloud" nell'architettura generale:

```
ESP32 #2 (Gateway) --HTTP--> [ QUESTO COMPONENTE ] --> Database
```

Fuori dallo scope di questo componente, volutamente: l'inferenza ML sulle
immagini (task successivo, separato).

## Componenti implementati

### 1. API — `backend/api/`

Servizio FastAPI con due endpoint:

- `GET /health` — verifica di attività del servizio
- `POST /cloud/ingest` — endpoint principale, riceve il payload dal gateway

Il servizio riconosce le due modalità definite nello schema di rete e le
gestisce con logica separata:

- **`mode: "reference"`** — verifica che `image_ref` esista realmente nel
  dataset attivo (`dataset_id`), prima di accettare la richiesta
- **`mode: "captured"`** — decodifica l'immagine ricevuta in base64 e la
  salva su storage locale, registrandone il percorso

Ogni payload viene validato contro lo schema atteso (tipi, campi
obbligatori) prima di qualunque elaborazione: un payload malformato viene
respinto con un errore esplicito, non processato parzialmente.

### 2. Autenticazione toggleable

Il controllo dell'header `X-API-Key` è applicato a ogni chiamata a
`/cloud/ingest`, ma è attivo solo se la variabile d'ambiente
`AUTH_ENABLED=true` è impostata. Questo permette di dimostrare in demo sia
lo scenario "sistema aperto" sia lo scenario "sistema protetto", come
discusso nel documento di design.

### 3. Database — `backend/database/init.sql`

Una singola tabella, `acquisitions`, che registra ogni richiesta accettata:
identificatori (`request_id`, `node_id`, `seq_num`), timestamp assegnato dal
gateway, e i campi specifici della modalità (`image_ref`/`dataset_id` per
`reference`, `capture_id`/`image_path` per `captured`).

### 4. Containerizzazione — `docker/docker-compose.yml`

Due servizi: `api` (il backend) e `db` (PostgreSQL). Il dataset delle
immagini è montato come **volume esterno** (non copiato dentro l'immagine
Docker), così il dataset attivo può essere cambiato modificando solo il
percorso nel `docker-compose.yml`, senza dover ricostruire l'immagine — è
la scelta che permette in futuro di passare dal dataset BPA a un altro
(es. CIFAR-10) senza toccare codice.

## Failure mode verificato in questo task

Il sistema è stato testato anche nel caso in cui il gateway invii un
`image_ref` che non esiste nel dataset attivo (es. dataset cambiato, o
pacchetto rimasto in coda con un riferimento non più valido): il backend
risponde con un errore esplicito (404) invece di fallire in modo generico
— coerente con quanto previsto nella tabella dei failure modes del
documento di design.

## Cosa NON fa (ancora) questo componente

- Non esegue inferenza ML sulle immagini ricevute
- Non implementa la logica di retry/coda (quella vive lato Gateway, non qui)
- Non gestisce ancora la modalità `captured` con frammentazione ESP-NOW
  (il backend sa già ricevere un'immagine base64 completa, ma il firmware
  che la produce via fotocamera è un task successivo)

## Come si inserisce nel progetto complessivo

Questo backend è stato sviluppato e verificato **indipendentemente dagli
ESP32 fisici**, usando richieste HTTP simulate (`curl`) che rispettano
esattamente lo schema concordato. Questo era l'obiettivo dichiarato
dell'ordine di sviluppo scelto: avere backend e app mobile pronti e
testati, in modo che il firmware del Gateway (quando sviluppato) debba
solo collegarsi a componenti già funzionanti, invece di richiedere lo
sviluppo simultaneo di tutto lo stack a ridosso della consegna.
