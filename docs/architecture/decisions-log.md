# Diario delle decisioni — perché il progetto è fatto così

Questo documento non ripete cosa è stato costruito (per quello c'è la
`roadmap.md` e i documenti tecnici in `docs/architecture/`) — spiega
**perché** ho scelto una strada invece di un'altra, e i problemi
importanti affrontati lungo il percorso.

---

## 1. Perché l'architettura è ESP32 → Gateway → Mobile/Cloud (e non diversamente)

Il corso IoT richiede almeno 2 ESP32, con una forma di raccolta dati e
una forma di analisi. La scelta di dividere "acquisizione" (ESP32 #1) da
"gateway/instradamento" (ESP32 #2) replica un pattern IoT reale: un nodo
sensore isolato che parla solo localmente, e un gateway che fa da ponte
verso la rete più ampia (protocol translation da ESP-NOW a WiFi/HTTP).
Un solo ESP32 che facesse tutto avrebbe perso proprio questo aspetto di
"rete distribuita a due livelli", che è il cuore della valutazione IoT.

## 2. Perché ESP32 #1 manda solo un riferimento, non l'immagine vera (modalità `reference`)

Primo istinto sarebbe stato far viaggiare l'immagine vera byte-per-byte
da subito. Scartato perché:
- ESP-NOW ha un limite di payload di ~250 byte — un'immagine reale
  richiederebbe frammentazione, complessità firmware non necessaria per
  il dataset BPA (che già esiste su disco)
- Il requisito del corso dice esplicitamente "using existing datasets is
  fine" — non serve dimostrare acquisizione hardware reale per superare
  la valutazione

La modalità `captured` (immagine vera, per foto scattate con l'ESP32-CAM
o dal telefono) resta comunque prevista nello schema — serve per la demo
con oggetti reali (es. CIFAR-10-like), tenuta volutamente separata dal
flusso principale BPA per non appesantirlo.

## 3. Perché il dataset è montato come volume esterno, non copiato nell'immagine Docker

Per poter cambiare dataset attivo (BPA → CIFAR-10, per la parte
adversarial) senza dover ricostruire l'immagine Docker — cambia solo il
percorso nel `docker-compose.yml`.

## 4. Perché VGG16 (deep learning) invece della pipeline classica, per l'inferenza mobile

La pipeline classica (Random Forest/SVM/ecc.) richiede un preprocessing
complesso (Otsu, filtri morfologici, estrazione di 25 feature) scritto in
Python/scikit-image. Portarlo su Android avrebbe richiesto riscriverlo
da zero in Java, con alto rischio di piccole discrepanze che rovinano
l'accuratezza. VGG16 invece si converte direttamente in TensorFlow Lite,
con preprocessing semplice (resize + normalizzazione) — nessuna
riscrittura di logica complessa necessaria.

## 5. Il problema più lungo: allineare la libreria TensorFlow Lite Android alla versione del modello

**Sintomo**: l'app andava in crash all'avvio con
`Didn't find op for builtin opcode 'FULLY_CONNECTED' version '12'`.

**Causa**: il modello `.tflite` era stato convertito con TensorFlow 2.21
(molto recente), che usa internamente operazioni in versioni che le
librerie TensorFlow Lite più vecchie per Android (provate: 2.9.0, 2.13.0,
2.14.0, 2.16.1) non riconoscono.

**Percorso di risoluzione** (utile ricordarlo se il problema si
ripresenta con un futuro aggiornamento del modello):
1. Provato a riconvertire il modello con TensorFlow più vecchio — fallito,
   PyPI non distribuisce più `tensorflow==2.13.0`, e la 2.16+ introduce
   Keras 3 con problemi di compatibilità nel caricare modelli salvati con
   Keras 2
2. Risolto salendo di versione la libreria Android, non scendendo: la
   combinazione funzionante è **TensorFlow Lite 2.17.0** lato Android con
   il modello originale (convertito con TF 2.21) — è la prima versione
   Android che riconosce l'opcode richiesto
3. **Lezione**: se in futuro si aggiorna il modello con una versione di
   TensorFlow ancora più recente, è probabile che serva alzare di nuovo la
   versione della libreria Android — non è garantito che 2.17.0 basti per
   sempre

## 6. Perché è stato necessario un downgrade di Gradle/AGP (9.7/9.3 → 8.9/8.7)

**Sintomo collaterale del punto 5**: durante i tentativi con diverse
versioni di TensorFlow Lite, compariva sempre lo stesso errore di
manifest merger (`Namespace 'org.tensorflow.lite' is used in multiple
modules`), indipendentemente dalla versione della libreria provata.

**Causa reale**: non era la libreria TensorFlow, ma la combinazione
Gradle 9.7.0 / Android Gradle Plugin 9.3.1 (versioni molto recenti,
quasi da anteprima), che applica controlli di namespace duplicato molto
più severi delle versioni precedenti. Confermato perché lo stesso errore
si ripeteva identico su tre versioni diverse di TensorFlow Lite.

**Soluzione**: downgrade a Gradle 8.9 + AGP 8.7.0. Questo ha richiesto anche aggiornare
la sintassi in `build.gradle.kts` (es. `compileSdk = 34` invece della
sintassi a blocco `compileSdk { version = release(37) }`, che esiste solo
in AGP 9.x).

**Lezione**: quando un errore identico persiste attraverso più tentativi
di modificare la stessa dipendenza, vale la pena sospettare lo strumento
di build stesso, non solo la libreria.

## 7. Perché il preprocessing dell'immagine (mean subtraction VGG16) e non normalizzazione /255

Il modello è stato addestrato con
`tensorflow.keras.applications.vgg16.preprocess_input`, che converte
RGB→BGR e sottrae le medie ImageNet per canale (`103.939, 116.779,
123.68`) — non una normalizzazione generica a [0,1]. Usare `/255` (errore
commesso inizialmente in uno script di test) produceva risultati non
allineati con il modello reale. Verificato confrontando `.keras` e
`.tflite` sulla stessa immagine con lo stesso preprocessing: risultati
quasi identici (differenza <0.1%), a conferma che la conversione
preserva il comportamento del modello quando il preprocessing è corretto.

## 8. Discrepanza di confidenza tra Python e Android — perché resta un limite noto

Vedi `docs/architecture/inference-precision-notes.md` per il dettaglio
completo. In sintesi: la classe predetta è sempre coerente tra le due
piattaforme, ma la confidenza numerica differisce leggermente per via di
decoder JPEG diversi (PIL/libjpeg vs decoder nativo Android) e algoritmi
di ridimensionamento diversi (risolto parzialmente implementando un
resize bicubico manuale in Java, dato che Android non offre un bicubico
nativo). Deciso di non inseguire una parità esatta al bit.

## 9. Perché l'autenticazione è solo sul canale HTTP (gateway↔mobile/cloud), non su ESP-NOW

ESP-NOW collega due dispositivi fisicamente controllati dallo
sviluppatore (i due ESP32 personali) — non è il canale esposto a un
ipotetico attaccante. Il canale HTTP invece è quello raggiungibile da un
dispositivo esterno non autorizzato, quindi è lì che ha senso il
meccanismo di difesa (API key), reso volutamente disattivabile per poter
mostrare in demo sia lo scenario vulnerabile che quello protetto.

## 10. Perché il profilo utente è locale (no autenticazione vera)

Nessun requisito del corso richiede esplicitamente un sistema di account.
Un profilo locale semplice (nome salvato sul telefono) dà comunque "chi
ha fatto la foto" per distinguere le fonti nella cronologia, senza il
costo di costruire un vero sistema di autenticazione (che richiederebbe
un backend con gestione utenti — pezzo di infrastruttura a sé). Se in
futuro servisse un'autenticazione vera, si può aggiungere senza
riscrivere le altre schermate, che dipendono solo da "il profilo attivo"
in modo generico.

## 11. Perché niente libreria di grafici esterna per il grafico delle probabilità

Dopo l'esperienza del punto 6 (conflitti di dipendenze TensorFlow), si è
scelto di implementare il grafico a barre con componenti Android nativi
(`ProgressBar` + `TextView` per ciascuna classe) invece di aggiungere una
libreria di charting (es. MPAndroidChart) — evita di riesporsi al rischio
di conflitti di build per un componente visivamente semplice che non lo
giustifica.

## 12. Cronologia: perché SQLite nativo (non Room), e perché le immagini non stanno nel database
 
**Il bisogno**: la Home teneva solo l'ultimo risultato, in memoria
(`AppState`) — sparisce alla chiusura dell'app. La Cronologia richiede
persistenza reale, che sopravviva al riavvio.
 
**Perché SQLite nativo (`SQLiteOpenHelper`) e non Room**: Room è la
libreria standard moderna per database locali su Android, ma richiede un
annotation processor (`kapt`/`annotationProcessor`) come dipendenza
Gradle aggiuntiva. Dato quanto tempo era già stato speso a risolvere
conflitti di dipendenze con TensorFlow Lite (vedi punto 5-6), ho
scelto di restare su `SQLiteOpenHelper`, incluso nativamente in Android
senza aggiungere nessuna dipendenza — più verboso da scrivere a mano, ma
zero rischio di nuovi conflitti di build.
 
**Perché le immagini sono file, non righe del database**: un database
relazionale è pensato per dati strutturati piccoli (numeri, stringhe),
non per BLOB di alcuni MB come una foto. Le immagini vengono salvate
come file JPEG nello storage interno dell'app (`getFilesDir()`,
cartella `history_images/`); il database tiene solo il **percorso** del
file, non i byte dell'immagine.
 
## 13. Perché la Cronologia è divisa in HistoryItem / HistoryDbHelper / HistoryAdapter
 
Tre responsabilità separate, pattern standard Android:
 
- **`HistoryItem`**: solo un contenitore di dati (id, nome file, classe,
  confidenza, probabilità, fonte, timestamp, percorso immagine) — nessuna
  logica, serve solo a passare i dati da un pezzo all'altro in modo
  ordinato
- **`HistoryDbHelper`**: unico punto che parla con il database SQLite —
  sa come salvare (`insert`) e leggere (`getAll`) i record. Se in futuro
  si cambiasse tecnologia di persistenza (es. verso Room), è l'unico
  file da riscrivere: il resto dell'app continuerebbe a ricevere
  `HistoryItem`, senza saperne l'origine
- **`HistoryAdapter`**: traduce la lista di `HistoryItem` in righe visive
  nella `RecyclerView` (la lista scorrevole della schermata Cronologia)
  — sa come disegnare una riga, non sa nulla di database
Questa separazione (dati / accesso ai dati / visualizzazione) evita che
`HistoryFragment` debba occuparsi sia di leggere il database sia di
disegnare la lista sia di gestire i click — ogni pezzo ha un solo
compito, più facile da modificare o testare in isolamento.
 
## 14. Perché la lettura del database avviene su un thread separato
 
Leggere da un database, anche locale, è un'operazione potenzialmente
lenta (I/O su disco). Se fatta sul thread principale (quello che disegna
l'interfaccia), l'app si blocca visibilmente per la durata della lettura
— con dataset piccoli non si nota, ma è comunque la pratica corretta e
diventa necessaria se la cronologia crescerà. `HistoryFragment` usa un
`ExecutorService` per leggere in background, poi torna al thread
principale solo per aggiornare la lista visibile.
 
## 15. Perché ResultDetailActivity ora legge da due fonti diverse (AppState o Intent)
 
Il dettaglio serve in due situazioni diverse: aperto dalla Home (sempre
"l'ultimo risultato", già disponibile in `AppState`) o aperto dalla
Cronologia (un risultato passato qualsiasi, che deve arrivare da fuori,
via `Intent`). Invece di duplicare la schermata di dettaglio in due
Activity quasi identiche, `ResultDetailActivity` controlla se l'Intent
contiene dati (`EXTRA_LABEL` presente) — se sì li usa (caso Cronologia),
altrimenti ricade su `AppState.lastResult` (caso Home). Un solo file,
riusato per entrambi i percorsi.

## 16. OutOfMemoryError con immagini ad alta risoluzione — due cause distinte, una soluzione comune
 
**Primo sintomo**: l'app crashava con `OutOfMemoryError` durante la
classificazione di un'immagine arrivata via `/mobile/ingest` (test
curl), ma non con foto normali scattate dal telefono.
 
**Causa**: il resize bicubico manuale (vedi punto 8) copia l'intera
immagine sorgente in un array Java (`getPixels`) prima di ridimensionarla.
Per un'immagine sorgente molto grande (foto originale del dataset,
diversi milioni di pixel), quell'array può pesare centinaia di MB — oltre
il limite di memoria che Android assegna a un'app (tipicamente ~192MB).
Il resize nativo di Android (`Bitmap.createScaledBitmap`, usato prima
dell'allineamento con PIL) non aveva questo problema perché non duplica
mai l'immagine intera in un array separato.
 
**Secondo sintomo**: dopo aver corretto il primo problema, toccare
quella stessa immagine nella Cronologia per vederne il dettaglio
causava un nuovo crash — la classificazione ora funzionava, ma la
*visualizzazione* a piena risoluzione no.
 
**Causa**: l'immagine veniva tenuta/salvata/ricaricata sempre alla sua
risoluzione originale in più punti (in memoria per la Home, su file per
la Cronologia, ricaricata da file per il Dettaglio) — nessuno di questi
punti era di per sé il problema quanto la somma: immagini enormi
sopravvivono e vengono ri-decodificate più volte nell'app.
 
**Soluzione (alla radice, non punto per punto)**: invece di correggere
ogni singolo punto che tocca l'immagine, si limitano le dimensioni
**una sola volta**, subito dopo la decodifica iniziale (in
`MainActivity.classifyCaptured` e in `ManualTestFragment`), tramite
`ImageUtils.capMaxDimension` (massimo 1024px sul lato più lungo). Da
quel momento in poi, ogni componente a valle (classificazione,
salvataggio, miniature, dettaglio) lavora già su una versione sicura
dell'immagine — nessun altro punto del codice deve preoccuparsene.
 
**Lezione**: quando un problema di memoria si ripresenta in più punti
diversi collegati agli stessi dati, conviene risolverlo alla sorgente
(appena i dati entrano nel sistema) invece di rincorrerlo in ogni punto
dove i dati vengono riusati.
 
## 17. Eliminazione dalla Cronologia — perché tieni-premuto e non uno swipe o un'icona cestino
 
Un semplice tocco è già usato per aprire il dettaglio — serviva un
gesto diverso per l'eliminazione, per evitare cancellazioni accidentali.
Il tieni-premuto (`long click`) è il pattern standard Android per azioni
"secondarie" su un elemento di lista (è lo stesso gesto usato, ad
esempio, per selezionare messaggi in un'app di chat). Rispetto a uno
swipe-to-delete o un'icona cestino sempre visibile, è più semplice da
implementare (nessun layout aggiuntivo per riga, nessuna libreria di
gesture) e comunque immediatamente riconoscibile per un utente Android.
La conferma con dialogo (invece di cancellare subito al tocco lungo) è
una misura di sicurezza minima contro tocchi accidentali, dato che
l'operazione non è reversibile (elimina anche il file immagine, non solo
il record).


## 18. Perché la porta del server non cambia "a caldo"
 
Il server (`IngestServer`) viene creato una sola volta in
`MainActivity.onCreate` e resta attivo per tutta la vita del processo
app. Riconfigurarlo su una porta diversa mentre e' gia' in ascolto
richiederebbe fermarlo e ricrearlo mentre l'app e' in esecuzione — con
rischio di richieste a meta' o problemi di sincronizzazione con lo stato
condiviso (`AppState`). Si e' scelto di limitare la modifica della porta
a "effettiva al prossimo riavvio dell'app", dichiarato esplicitamente
nell'interfaccia — piu' semplice e sicuro di gestire un riavvio del
server a runtime


## 25. Limite noto: la nota non è visibile aprendo il Dettaglio dalla Home
 
`ResultDetailActivity`, quando aperta dalla Home (percorso "ultimo
risultato", senza extra nell'Intent), legge i dati da
`AppState.lastResult` — che non contiene la nota, perché `AppState` è
uno stato "in memoria" popolato al momento della classificazione, prima
che qualunque nota possa essere stata scritta. Il campo nota resta
comunque modificabile e salvabile in quel percorso (l'id è disponibile),
solo non mostra un valore preesistente. Corretto sarebbe far interrogare
il database anche al percorso Home, ma non ancora fatto — lasciato come
nota per un prossimo intervento se necessario.


## 20 Perché l'attacco ridimensiona l'immagine a 224×224 prima di partire
 
**Sintomo**: eseguire Boundary su un'immagine ad alta risoluzione
causava avvisi di allocazione di memoria eccessiva e, sul PC
dell'utente, un blocco tale da richiedere il riavvio.
 
**Causa**: lo script (nella versione originale del tirocinio, pensata
per girare su un server con GPU A100 e molta RAM) lasciava che l'attacco
perturbasse l'immagine alla sua risoluzione originale, anche se il
modello VGG16 vede comunque solo un'immagine ridimensionata a 224×224
(la pipeline di predizione la ridimensiona ad ogni chiamata). Un attacco
come Boundary richiama il modello centinaia di volte per iterazione:
lavorare su un'immagine di alcuni megapixel invece che su 224×224
moltiplica inutilmente il costo di memoria e calcolo di ogni chiamata,
senza alcun beneficio (il risultato finale che il modello "vede" sarebbe
comunque equivalente).
 
**Soluzione**: ridimensionare l'immagine a 224×224 (stesso algoritmo
Lanczos già usato dalla pipeline di predizione) *prima* di darla
all'attacco, nella funzione condivisa `run_art_attack()` — beneficio
automatico sia da CLI sia dal futuro endpoint, dato che entrambi la
richiamano.
 
**Lezione**: il codice di ricerca (pensato per un ambiente con risorse
abbondanti, server con GPU dedicata) spesso fa assunzioni implicite
sulle risorse disponibili che non reggono quando portato su hardware
consumer (laptop, senza GPU) — vale la pena controllare esplicitamente
dove il codice lavora "più in grande" del necessario prima di integrarlo
in un contesto con vincoli hardware diversi.


## 31. Perché l'attacco gira in un Foreground Service, non più dentro il Fragment
 
**Il bisogno**: gli attacchi possono richiedere diversi minuti (osservato
fino a ~8 minuti per Boundary senza GPU) - tenere l'utente bloccato sulla
stessa schermata per tutto quel tempo, senza poter fare altro col
telefono, e senza sapere quando l'attacco finisce se non guardando lo
schermo, era un'esperienza scomoda.
 
**Perché un Foreground Service e non un semplice thread in background
nel Fragment**: un thread avviato da un Fragment/Activity puo' essere
interrotto dal sistema se l'utente esce dall'app e il processo viene
gia deprioritizzato o terminato per liberare memoria - Android non
garantisce che un lavoro "invisibile" continui. Un Foreground Service e'
il meccanismo Android pensato apposta per lavori che l'utente ha
esplicitamente avviato e che devono continuare anche se l'app va in
background: richiede una notifica persistente e visibile (che informa
l'utente che qualcosa sta consumando risorse), in cambio di una
"protezione" dalla terminazione anticipata da parte del sistema.
 
**Cosa si e' spostato**: l'intera logica di chiamata al backend +
salvataggio in Cronologia e' stata spostata da `AdversarialTestFragment`
a `AdversarialAttackService`, che vive indipendentemente dal ciclo di
vita della schermata - l'utente puo' navigare altrove, uscire
dall'app, persino bloccare il telefono, e il servizio continua. Il
Fragment ora si limita ad avviare il servizio e mostrare un messaggio
breve; il risultato si trova nella Cronologia attacchi (o toccando la
notifica finale), non piu' nella stessa schermata da cui si e' partiti.

## 32. Perché il Cloud non ha un servizio ML separato
 
Per completare l'inferenza ML lato Cloud (task rimasto aperto da
mesi), la scelta e' stata riusare il servizio `robustness` gia'
esistente (che ha il modello VGG16 caricato in memoria per gli attacchi
adversarial) invece di crearne uno nuovo dedicato. `robustness` esponeva
gia' un endpoint pensato per questo esatto scopo,
`/robustness/predict` - originariamente creato per servire il servizio
SimBA (che ha bisogno di interrogare il modello senza caricare
TensorFlow nel proprio container PyTorch), ma altrettanto adatto a
essere chiamato dal backend `api`.
 
**Perche' non un servizio separato**: caricare TensorFlow e il modello
VGG16 una seconda volta in un container a se' avrebbe raddoppiato il
consumo di RAM per lo stesso identico modello - uno spreco evitabile,
specialmente dato che la macchina di sviluppo ha gia' mostrato limiti
di memoria in passato (vedi i problemi di blocco PC con Docker+Chrome
descritti in una sezione precedente).
 
La classificazione (`classify_and_store`) e' intenzionalmente
"a parte" dal salvataggio della richiesta: se il servizio robustness
non e' raggiungibile, la riga resta comunque salvata nel database
(senza risultato), e la risposta al Gateway non viene mai bloccata da
un problema di classificazione - coerente con lo stesso principio di
tolleranza ai guasti gia' applicato altrove nel progetto (es. l'app che
risponde comunque 200 OK anche se la classificazione locale fallisce).

## 39. Il percorso per far funzionare fotocamera, display e pulsanti insieme
 
Integrare questi tre sistemi nello stesso firmware ha richiesto
risolvere, in sequenza, diversi problemi - vale la pena ripercorrerli.
 
**1. ESP-IDF troppo vecchia per il driver fotocamera**: la versione
installata (5.5.1) non supportava il driver DVP necessario per questa
combinazione scheda/IDF - risolto aggiornando a 5.5.2 (aggiornamento di
patch, non ha richiesto modifiche ai progetti Gateway/Acquisition Node
gia' funzionanti).
 
**2. Versione del pacchetto BSP presa "troppo nuova" nonostante il
blocco**: il gestore dipendenze di ESP-IDF tratta un numero di versione
scritto da solo (es. `"5.0.2"`) come "compatibile con, o piu' recente",
non come "esattamente questa" - serve il simbolo `=` esplicito
(`"=5.0.2"`) per bloccarla davvero. Una versione piu' recente (6.0.0)
usa un driver fotocamera diverso e incompatibile con `esp_camera.h`.
 
**3. Partizione flash troppo piccola**: il firmware, una volta aggiunto
tutto (fotocamera, display, LVGL), e' cresciuto oltre lo spazio
riservato di default (1MB) - risolto passando a una tabella di
partizioni "large" (circa 1.5MB) tramite `idf.py menuconfig`. Nessun
rischio per l'hardware (la scheda ha gia' dimostrato di supportare
questa dimensione, vedi il progetto di prova).
 
**4. PSRAM inizializzata nella modalita' sbagliata**: la scheda ha PSRAM
"Octal" (8 linee dati), ma la configurazione di `acquisition-node` (mai
aggiornata da quando il progetto conteneva solo WiFi/ESP-NOW) era
ancora impostata su "Quad" (4 linee) - la fotocamera falliva ad
allocare il proprio buffer perche' la PSRAM non veniva nemmeno
riconosciuta all'avvio (0 byte liberi). Risolto in
`menuconfig` → Component config → ESP PSRAM → Octal Mode.
 
**5. Conflitto tra pulsanti e fotocamera sullo stesso canale ADC**: i
quattro pulsanti MENU/PLAY/UP/DOWN della scheda condividono un solo
pin, letto come segnale analogico (ADC) - creare quei pulsanti (anche
il pacchetto ufficiale, non solo il tentativo di lettura manuale
scritto inizialmente) avvia un timer interno che li legge
periodicamente TUTTI insieme, andando in conflitto con l'uso che la
fotocamera fa delle risorse hardware condivise, causando un crash
(`assert failed: adc_oneshot_ll_set_output_bits`). **Soluzione**: il
pulsante BOOT della scheda e' invece su un pin digitale indipendente,
senza alcun ADC coinvolto - creando SOLO quel pulsante
(`iot_button_new_gpio_device`, non la funzione che li crea tutti
insieme), il conflitto non si presenta per costruzione. Un solo
pulsante fisico distingue pressione breve (scatta la fotocamera) da
pressione lunga (accende/spegne la modalita' automatica) - nessun
bisogno di riconfigurare/riflashare per cambiare comportamento a
runtime.

## 40. Frammentazione ESP-NOW per foto vere — protocollo e primo invio
 
Per mandare una foto vera (non solo un riferimento dataset) via
ESP-NOW, serviva un protocollo a parte: un pacchetto ESP-NOW puo'
contenere solo ~250 byte, mentre un JPEG anche piccolo pesa 10-15KB.
 
**Schema scelto**: due tipi di pacchetto (separati dallo schema
`reference` gia' esistente, mai toccato):
- **HEADER**, mandato una volta: byte totali, numero di frammenti
  attesi, CRC dell'immagine intera (per la verifica finale lato
  Gateway).
- **DATA**, uno per frammento (200 byte di payload ciascuno): indice
  progressivo e lunghezza (l'ultimo frammento e' piu' corto).
**Scoperta importante durante la progettazione**: la fotocamera cattura
in formato RGB565 grezzo (necessario per l'anteprima dal vivo), NON
JPEG - serve convertire con `frame2jpg` (gia' inclusa nel pacchetto
della fotocamera) solo al momento dello scatto, lasciando l'anteprima
continua invariata. Senza questa conversione, si sarebbero mandati
~150KB di dati grezzi invece di ~10-15KB di JPEG compresso.
 
**Pacing dell'invio**: il primo tentativo mandava i frammenti con una
pausa fissa tra uno e l'altro - causava perdite intermittenti
(`ESP_ERR_ESPNOW_NO_MEM`, coda interna di ESP-NOW riempita piu' in
fretta di quanto la radio riuscisse a svuotarla). Risolto aspettando la
VERA conferma di consegna (tramite la callback di invio + un
semaforo) prima di costruire e mandare il frammento successivo, invece
di una pausa "alla cieca".
 
## 41. Il lungo percorso per stabilizzare fotocamera+pulsanti+invio insieme
 
Mettere insieme fotocamera, display, pulsante fisico e invio
frammentato nello stesso firmware ha richiesto risolvere, in sequenza,
diversi problemi via via piu' sottili - vale la pena ripercorrerli.
 
**1. Stack troppo piccolo nel task sbagliato**: la prima versione
faceva scattare la foto direttamente dentro la callback del pulsante,
che pero' gira sul task di sistema dedicato ai timer (stack piccolo,
pensato per operazioni brevi) - la conversione JPEG (che ha bisogno di
parecchia memoria di lavoro) ne causava l'overflow, corrompendo memoria
adiacente. Risolto spostando il lavoro vero su un task dedicato,
con uno stack generoso, svegliato da una coda quando il pulsante viene
premuto.
 
**2. RAM interna insufficiente per creare le nuove task**: con
fotocamera+display+WiFi gia' attivi, restavano solo ~12KB di RAM
interna libera - insufficiente per gli stack delle nuove task.
Un primo tentativo (allocare gli stack in PSRAM) e' stato
scartato perche' Espressif lo sconsiglia esplicitamente per task che
toccano codice di sistema (causava un crash immediato,
"DoubleException"). La soluzione corretta e' stata liberare RAM interna
altrove: l'opzione "Allow .bss segment placed in external memory"
(`idf.py menuconfig` -> ESP PSRAM) sposta i dati interni di WiFi/rete in
PSRAM, raddoppiando la RAM interna libera (da ~12KB a ~24KB) - questo
si', esplicitamente supportato da Espressif per questo scenario.
 
**3. Un vero bug di lettura oltre i limiti, presente fin dall'inizio**:
il codice dell'anteprima (copiato dall'esempio ufficiale Espressif)
copiava sempre una quantita' fissa di byte dal fotogramma catturato
(dimensionata per la risoluzione del display), anche quando il
fotogramma vero era piu' piccolo (confermato dagli avvisi
"FB-SIZE: X != 115200" visti nei log) - una lettura oltre la fine del
buffer vero della fotocamera, ripetuta a ogni singolo fotogramma
dell'anteprima continua (non solo agli scatti). Molto probabilmente la
causa principale della maggior parte dei crash osservati fino a quel
punto. Corretto copiando solo `min(dimensione_buffer_display,
dimensione_fotogramma_vero)` byte.
 
**4. Rischio di blocco totale (deadlock) per un tentativo di
mitigazione successivo**: per escludere un'interferenza tra l'anteprima
e la conversione JPEG (entrambe candidate a toccare la PSRAM nello
stesso istante), si e' provato a sospendere di netto il task
dell'anteprima (`vTaskSuspend`) durante lo scatto - ma sospendere un
task puo' fermarlo in QUALSIASI istante della sua esecuzione, anche
mentre tiene un mutex, lasciandolo bloccato per sempre (osservato: schermo
e log completamente bloccati, nessun modo di uscirne se non
riavviando). Corretto sostituendo la sospensione forzata con un segnale
cooperativo (un flag booleano) che l'anteprima stessa controlla
all'inizio di ogni ciclo, fermandosi da sola solo in un punto sicuro
(mai mentre tiene un lucchetto).
 
**Limite noto, non risolto**: anche dopo tutte queste correzioni,
resta un crash residuo (corruzione di memoria dentro la libreria di
conversione JPEG, `frame2jpg`/`fmt2jpg`) in circa un terzo degli
scatti, con causa profonda non isolata con certezza nonostante
un'indagine approfondita (esclusi: conflitto ADC, stack insufficiente,
invio troppo rapido, lettura oltre i limiti nell'anteprima,
interferenza display/LVGL, sospensione pericolosa di task). Ipotesi
principale rimasta: un bug piu' sottile nella libreria stessa
(`esp32-camera`), o un margine di risorse ancora troppo stretto per
questa combinazione specifica di sottosistemi tutti attivi insieme su
un chip con risorse limitate. **Decisione presa**: accettare questo
limite documentato piuttosto che continuare a investigare
indefinitamente - il sistema funziona nella maggioranza dei casi, una
base solida e dimostrabile per gli scopi del progetto.
 
 ## 42. Ricezione e ricomposizione delle foto lato Gateway
 
Completando il lato Acquisition Node (sezioni 40-41), mancava la parte
simmetrica sul Gateway: riconoscere i nuovi pacchetti (intestazione +
frammenti, 212 byte, distinti dai `reference` di 76 byte tramite la
sola dimensione ricevuta), ricomporli in un file JPEG completo,
verificarne l'integrità, e inoltrarli.
 
**Scelte di design**:
- **Una sola ricomposizione alla volta** - coerente con l'Acquisition
  Node, che ha un raffreddamento tra uno scatto e l'altro e non ne
  manda mai due in parallelo. Se arriva una nuova intestazione mentre
  la precedente non era completa, quella vecchia viene considerata
  fallita e scartata (evita di restare bloccati su uno scatto perso per
  sempre).
- **Timeout indipendente dai nuovi arrivi** (15 secondi) - un task
  dedicato controlla periodicamente se la ricomposizione in corso ha
  superato il tempo massimo, cosi' un tentativo fallito viene rilevato
  anche se non arriva mai un nuovo scatto a "smascherarlo".
- **Verifica doppia dell'integrità**: il CRC di ogni singolo pacchetto
  (rileva corruzione di un frammento in transito, gia' presente anche
  nei `reference`) PIU' il CRC dell'immagine intera dichiarato
  nell'intestazione (rileva un frammento mancante/sostituito che
  individualmente sembrava valido, ma la cui assenza corromperebbe il
  file finale).
- **Le foto catturate NON usano la coda di retry** dei `reference` - la
  coda esistente e' dimensionata per il piccolo pacchetto a dimensione
  fissa `esp_now_packet_t`; una foto arriva gia' come buffer intero
  (fino a 15-20KB), riprovarla richiederebbe una coda separata capace
  di conservare interi file immagine. Scelta consapevole: se l'inoltro
  di una foto fallisce, per ora viene persa con un log chiaro
  (`*** SCATTO FALLITO ***`), non ritentata automaticamente - limite
  noto, accettato per lo scopo di questo progetto.
- **Notifica al backend rimandata apposta**: la funzione
  `notify_capture_failed` esiste gia' e logga chiaramente sul Gateway,
  ma non manda ancora nulla al backend (richiederebbe anche una
  modifica li', fuori scope per questa sessione dedicata al solo
  Gateway - task separato, gia' segnato in roadmap).
**Verificato end-to-end con successo** su foto vere (non dataset):
cattura sulla fotocamera, conversione JPEG, invio frammentato via
ESP-NOW, ricezione e ricomposizione sul Gateway, verifica CRC positiva,
costruzione della richiesta HTTP (base64 via mbedtls, gia' incluso per
HTTPS - nessuna nuova dipendenza). L'unico anello che continua a
fallire e' l'inoltro finale verso il telefono - confermato essere lo
stesso identico problema di rete/hotspot gia' noto per i pacchetti
`reference` (stesso tipo di errore, `ESP_ERR_HTTP_CONNECT` /
`select() timeout`, ben prima che la dimensione della foto entri in
gioco), non un difetto legato alla dimensione dei dati o al codice di
questo task.