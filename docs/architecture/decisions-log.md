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