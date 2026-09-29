# Network Design — ESP32 Acquisition/Gateway ↔ Mobile/Cloud

## 1. Panoramica dei canali

```
ESP32 #1 (Acquisition Node)
        | ESP-NOW
        v
ESP32 #2 (Gateway)
        | WiFi / HTTP
        v
   Mobile  oppure  Cloud
```

Due canali di trasporto distinti:

- **ESP32 #1 → ESP32 #2**: ESP-NOW (peer-to-peer, corto raggio, nessun router necessario)
- **ESP32 #2 → Mobile/Cloud**: WiFi / HTTP (richiede rete IP raggiungibile)

## 2. Modalità di acquisizione

Il nodo #1 supporta due modalità, selezionabili in configurazione:

| Modalità | Uso previsto | Cosa viaggia in rete |
|---|---|---|
| `reference` | Dataset BPA (bloodstain), e in generale qualunque dataset esistente | Solo un riferimento testuale all'immagine (nome file), l'immagine reale resta nello storage condiviso del backend |
| `captured` | Demo e/o acquisizione reale (es. ESP32-CAM che fotografa un oggetto reale, es. da confrontare con classi CIFAR-10-like) | L'immagine catturata (JPEG), frammentata su ESP-NOW |


## 3. Schema ESP-NOW (ESP32 #1 → ESP32 #2)

Payload compatto (limite ESP-NOW ~250 byte):

| Campo | Tipo | Descrizione |
|---|---|---|
| `node_id` | uint8 | Identificatore del nodo mittente |
| `seq_num` | uint32 | Numero di sequenza monotono — rileva pacchetti persi o duplicati |
| `mode` | uint8 (enum: 0=reference, 1=captured) | Modalità di acquisizione |
| `timestamp` | uint32 | Millis da boot (non affidabile come orario assoluto — il timestamp reale viene assegnato dal gateway) |
| `image_ref` | char[64] | Usato solo se `mode = reference`: nome/riferimento del file nel dataset attivo |
| `crc` | uint16 | Checksum del pacchetto, per scartare pacchetti corrotti |

Per `mode = captured`, il pacchetto sopra è solo l'header iniziale; i chunk immagine successivi seguono un formato a parte.

## 4. Schema HTTP (ESP32 #2 → Mobile/Cloud)

### 4.1 Modalità `reference`

```json
{
  "request_id": "gw01-000042",
  "mode": "reference",
  "node_id": 1,
  "seq_num": 42,
  "gateway_timestamp": "2026-08-19T10:15:30Z",
  "image_ref": "gunshot_014.jpg",
  "dataset_id": "bpa_v1",
  "retry_attempt": 0
}
```

### 4.2 Modalità `captured`

```json
{
  "request_id": "gw01-000043",
  "mode": "captured",
  "node_id": 1,
  "seq_num": 43,
  "gateway_timestamp": "2026-08-19T10:16:05Z",
  "capture_id": "cap-000043",
  "image_data": "<JPEG base64>",
  "retry_attempt": 0
}
```

### 4.3 Note sui campi comuni

- `request_id`: generato dal gateway (`node_id` + `seq_num`), identificatore univoco end-to-end per correlare risultati nel database
- `gateway_timestamp`: assegnato dal gateway 
- `dataset_id` (solo `reference`): rende il sistema agnostico rispetto al dataset attivo (BPA, CIFAR-10, ecc.), permettendo di cambiarlo via configurazione senza modificare lo schema
- `retry_attempt`: incrementato ad ogni tentativo di invio dalla coda locale di retry; utile anche come metrica di analisi

La destinazione (mobile vs cloud) non è un campo nel payload: è implicita nell'endpoint HTTP invocato dal gateway (es. `/mobile/ingest` vs `/cloud/ingest`).

## 5. Autenticazione (toggleable)

Applicata solo sul canale HTTP (gateway → mobile/cloud), non su ESP-NOW: il canale ESP-NOW collega due dispositivi fisicamente controllati dallo sviluppatore, mentre il canale HTTP è quello esposto a un ipotetico dispositivo esterno non autorizzato — il vettore di minaccia rilevante per il tema adversarial del progetto.

- Meccanismo: shared API key, inviata come header `X-API-Key: <valore>`
- Verifica lato ricevente (mobile/backend): richiesta rifiutata (401) se l'header manca o non corrisponde
- Attivabile/disattivabile via configurazione (non hardcoded):
  - Gateway: parametro di configurazione firmware
  - Backend: variabile d'ambiente (`AUTH_ENABLED=true|false`) in `docker-compose.yml`
- Scopo dimostrativo: permette di mostrare in demo sia lo scenario "sistema aperto" (richieste dirette al backend, bypassando il gateway, vengono accettate) sia lo scenario "sistema protetto" (le stesse richieste vengono respinte) — un confronto before/after utile per la presentazione del tema robustezza

## 6. Scelta del protocollo applicativo: HTTP vs MQTT

| Aspetto | HTTP | MQTT |
|---|---|---|
| Modello | Request/response diretto | Publish/subscribe tramite broker |
| Adatto quando | Un mittente, una destinazione nota | Un evento, più destinatari potenziali |
| Overhead firmware | Basso (client HTTP semplice) | Più alto (libreria MQTT, connessione persistente, keep-alive) |
| Componenti aggiuntivi | Nessuno | Richiede un broker (es. Mosquitto) nel backend |

**Decisione: HTTP come protocollo primario.** Il gateway instrada ogni pacchetto verso **una** destinazione decisa (mobile *oppure* cloud) — è strutturalmente un caso request/response, non un evento broadcast a più consumatori. MQTT sarebbe preferibile se in futuro si volesse notificare più destinatari dello stesso evento senza che il gateway ne conosca l'elenco esplicito (es. cloud per salvataggio + mobile per notifica push in parallelo).

MQTT resta una possibile estensione futura, non un fallback per l'assenza di WiFi: MQTT gira comunque su TCP/IP, quindi richiede la stessa infrastruttura di rete di HTTP e non risolve il problema di connettività assente.

## 7. Failure modes considerati

| Scenario | Gestito? | Soluzione |
|---|---|---|
| WiFi assente o interrotto (gateway → mobile/cloud) | Sì | Coda locale sul gateway con retry periodico; il pacchetto resta in coda finché la connettività non torna |
| Backend cloud irraggiungibile (container giù) | Sì | Timeout sulla richiesta HTTP dal gateway; il pacchetto rientra nella coda di retry invece di bloccare il gateway |
| App mobile non risponde (offload deciso verso mobile) | Sì | Timeout + fallback automatico verso cloud, se disponibile |
| Pacchetto corrotto/incompleto (ESP32#1 → gateway via ESP-NOW) | Sì | Campo `crc`: pacchetti con checksum non valido vengono scartati dal gateway |
| `image_ref` inesistente nel dataset attivo (es. dataset cambiato, pacchetto vecchio in coda) | Sì | Il backend/mobile risponde con errore esplicito (es. 404 applicativo); il gateway logga e scarta il pacchetto invece di ritentare all'infinito |
| Riavvio del gateway con coda di retry piena | **No — limite noto** | La coda vive in RAM: un riavvio la azzera, i pacchetti in attesa vengono persi. Scelta consapevole per limitare la complessità (nessuna persistenza su flash); dichiarata esplicitamente come limite del sistema, non un bug non individuato |

## 8. Estensioni opzionali (fuori dal core, da valutare solo a progetto stabile)

- Sostituzione/affiancamento di HTTP con MQTT (broker Mosquitto containerizzato)
