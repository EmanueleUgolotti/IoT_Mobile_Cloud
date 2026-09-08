# Guida pratica — come avviare tutto

Questo documento raccoglie i comandi per le operazioni che si ripetono
spesso: ambiente ESP-IDF, firmware Gateway, Docker, e verifiche di rete.
Non spiega le scelte progettuali (per quello vedi `decisions-log.md`),
solo "cosa digitare".

---

## 1. Ambiente ESP-IDF

### Attivare l'ambiente (ad ogni nuovo terminale)

```bash
. $HOME/esp/v5.5.1/esp-idf/export.sh
```

Il punto e lo spazio iniziali (`. `) sono importanti — senza, le
variabili d'ambiente non restano attive nel terminale.

**Attenzione**: se in quel terminale hai anche un venv Python generico
attivo (quello del progetto, `venv`), disattivalo prima:
```bash
deactivate
```
I due ambienti (ESP-IDF e il venv del progetto) vanno tenuti in
terminali separati, non mescolati nello stesso terminale.

Verifica che l'attivazione sia andata a buon fine:
```bash
idf.py --version
which python   # deve puntare dentro .espressif/python_env/..., non dentro venv/
```

### Se `idf.py menuconfig` da' errore di locale

```bash
export LC_ALL=C.utf8
export LANG=C.utf8
```
(minuscolo `utf8`, non `UTF-8` — su questo sistema conta la differenza)

---

## 2. Firmware Gateway — build, configurazione, flash

Tutti i comandi vanno lanciati da dentro `esp32/gateway/` (dopo aver
attivato l'ambiente ESP-IDF, punto 1):

```bash
cd ~/unitri/Magistrale/IoT_Mobile_Cloud/esp32/gateway
```

### Configurare WiFi e indirizzi backend (senza toccare il codice)

```bash
idf.py menuconfig
```
Naviga con le frecce fino a **"BPA Gateway Configuration"** (in fondo al
menu principale), premi Invio per entrarci. Da li' puoi impostare:
- Nome rete WiFi (SSID) e password
- Indirizzo IP e porta del backend Cloud (`api`, di solito porta 8000)
- Indirizzo IP e porta del telefono con l'app (di solito porta 8080)
- API key (lascia vuoto se `AUTH_ENABLED=false` sul backend)
- Selftest (pacchetto finto all'avvio, per testare senza l'Acquisition Node)

Salva con `S`, poi Invio; esci con `Q`.

**Nota sulla rete**: reti pubbliche con portale di accesso via browser
(WiFi cittadini/regionali, molte reti universitarie) NON funzionano per
il firmware - l'ESP32 non puo' gestire quel tipo di login. Usa una rete
domestica o l'hotspot di un telefono.

### Prima build su un nuovo target (una tantum, o se cambi scheda)

```bash
idf.py set-target esp32s3
```
Se da' errore "non e' una cartella cmake", la cartella `build/` e'
residua da un tentativo precedente:
```bash
rm -rf build
idf.py set-target esp32s3
```

### Build

```bash
idf.py build
```

### Trovare la porta seriale della board

```bash
ls /dev/ttyUSB* /dev/ttyACM* 2>/dev/null
```
La ESP32-S3 in uso appare tipicamente come `/dev/ttyACM0` (USB nativo),
non `/dev/ttyUSB0`.

### Flash + monitor (in un solo comando)

```bash
idf.py -p /dev/ttyACM0 flash monitor
```

Per uscire dal monitor: `Ctrl+]`.

### Se la porta risulta "occupata" (flash fallisce)

Qualche sessione precedente del monitor e' rimasta aperta e blocca la
porta. Da un altro terminale:
```bash
lsof /dev/ttyACM0
kill -9 <PID_mostrato>
```
(se `kill` da solo non basta, `sudo kill -9 <PID>`)

---

## 3. Docker — backend (api, db, robustness, robustness-simba)

Tutti i comandi vanno lanciati da dentro `docker/`:
```bash
cd ~/unitri/Magistrale/IoT_Mobile_Cloud/docker
```

### Avviare tutto (in background, il terminale torna libero)

```bash
docker compose up -d
```

### Vedere lo stato dei servizi

```bash
docker compose ps
```
Il servizio `robustness` deve mostrare `(healthy)` quando e' davvero
pronto (non solo "Up") — `robustness-simba` aspetta automaticamente che
lo sia prima di partire.

### Vedere i log (in tempo reale)

Tutti i servizi:
```bash
docker compose logs -f
```
Solo alcuni:
```bash
docker compose logs -f robustness robustness-simba
```
Solo le ultime N righe, senza restare "attaccato":
```bash
docker compose logs --tail=100 robustness
```

### Ricostruire un servizio dopo aver modificato il suo codice

```bash
docker compose build <nome-servizio>
docker compose up -d
```
Esempio, dopo aver modificato `backend/api/main.py`:
```bash
docker compose build api
docker compose up -d
```

### Riavviare un servizio (senza ricostruirlo) — utile se sembra "bloccato"

```bash
docker compose restart robustness
```

### Fermare tutto

```bash
docker compose down
```
(rimuove i container; i dati nel database restano salvati nel volume)

Per fermare solo un servizio, lasciando gli altri accesi:
```bash
docker compose stop api db
```

---

## 4. Trovare indirizzi IP

### IP del PC (per configurare app e Gateway)

```bash
ip addr show | grep "inet " | grep -v 127.0.0.1
```
Cerca l'indirizzo della tua interfaccia WiFi attuale (cambia a seconda
della rete a cui sei connesso in quel momento).

### IP del telefono

Impostazioni del telefono → WiFi → tocca la rete connessa → l'indirizzo
IP e' mostrato li'.

### Regola generale

**PC, Gateway e telefono con l'app devono essere sulla stessa rete
WiFi** per potersi parlare. Se cambi rete (es. passi all'hotspot di un
telefono), gli IP di tutti cambiano — vanno ri-controllati e
riconfigurati ovunque servono:
- Nel Gateway: `idf.py menuconfig` (indirizzi Cloud/Mobile)
- Nell'app: Impostazioni (indirizzo backend attacchi, indirizzo backend Cloud)

---

## 5. App Android — dopo aver modificato codice

In Android Studio:
1. Se compare la barra gialla "Sync Now", cliccala
2. `Build → Clean Project` (consigliato dopo molte modifiche)
3. `Build → Rebuild Project`
4. ▶️ Run, sul telefono collegato

### Vedere i log dell'app

Pannello **Logcat** in basso, filtro `BPA_IngestServer` (o il tag
specifico che cerchi).

### Forwarding USB (test senza WiFi, solo per l'app locale sul PC)

```bash
adb devices          # verifica che il telefono sia visto
adb forward tcp:8080 tcp:8080
```
Poi puoi usare `http://localhost:8080/...` dal PC invece dell'IP del
telefono - utile solo per test locali, non sostituisce il WiFi per la
comunicazione reale col Gateway.

---

## 6. Sequenza tipica per un test end-to-end completo

1. PC, Gateway e telefono con l'app sulla stessa rete WiFi (non
   pubblica/con portale di accesso)
2. `docker compose up -d` (dalla cartella `docker/`), aspetta `healthy`
3. Trova l'IP del PC (punto 4), aggiornalo se serve nell'app
   (Impostazioni) e nel Gateway (`idf.py menuconfig`)
4. Apri l'app sul telefono, lasciala in primo piano
5. `idf.py build && idf.py -p /dev/ttyACM0 flash monitor` (dal Gateway)
6. Osserva il log del Gateway e, in parallelo, il Logcat/la Cronologia
   dell'app