# Discrepanze numeriche tra inferenza Python e Android (TFLite)

## Il fenomeno osservato

Lo stesso modello (`bpa_vgg16.tflite`), sulla stessa immagine, produce **la stessa
classe predetta** ma **valori di confidenza leggermente diversi** a seconda
dell'ambiente di esecuzione:

| Ambiente | Classe predetta | Confidenza |
|---|---|---|
| Script Python (`test_tflite_inference.py`, PIL + TensorFlow Lite desktop) | gun | 98.93% |
| App Android (`Classifier.java`, BitmapFactory + TensorFlow Lite Android) | gun | ~100% (visualizzato, valore reale leggermente inferiore) |

La classificazione resta **corretta e coerente** in entrambi i casi — la
discrepanza riguarda solo la precisione del valore di confidenza, non l'esito
della classificazione.

## Cause identificate

### 1. Algoritmo di ridimensionamento dell'immagine

Il modello richiede input a 224×224 pixel; l'immagine originale va quindi
ridimensionata prima dell'inferenza.

- **Python (PIL)**: usa per default l'interpolazione **bicubica**
- **Android**: `Bitmap.createScaledBitmap` supporta solo l'interpolazione
  **bilineare** — nessun bicubico nativo disponibile

**Intervento fatto**: `Classifier.java` implementa un ridimensionamento
bicubico manuale (stesso parametro `a = -0.5` usato da PIL), per allineare
l'algoritmo tra le due piattaforme. Questo penso abbia ridotto la discrepanza, ma non
l'ha eliminata — vedi punto successivo.

### 2. Decodifica JPEG (causa residua, non risolta)

Prima ancora del ridimensionamento, il file immagine (JPEG) deve essere
decodificato in pixel grezzi:

- **Python**: usa `libjpeg` (tramite PIL)
- **Android**: usa il decoder JPEG nativo della piattaforma (`BitmapFactory`)

Le due librerie possono produrre valori dei pixel leggermente diversi già in
questa fase (differenze dell'ordine di ±1 su una scala 0-255), **prima**
ancora di qualunque resize o preprocessing successivo.

### 3. Amplificazione attraverso la rete neurale

Piccole differenze nei pixel di input si propagano attraverso i livelli
convoluzionali della rete, e possono essere amplificate — specialmente
quando il modello è già molto sicuro della propria predizione (come nel
caso osservato, 98.9% in Python è vicino alla saturazione): un modello
molto confidente tende ad amplificare ulteriormente piccole variazioni di
input verso output ancora più estremi (es. 99.6-99.9%), che poi vengono
visualizzati come "100%" per via dell'arrotondamento.

## Perché non è stato risolto completamente

Ottenere una parità numerica esatta tra le due piattaforme richiederebbe
usare esattamente la stessa libreria di decodifica JPEG e le stesse
operazioni in virgola mobile, nello stesso ordine, su entrambe le
piattaforme.

## Perché non è un problema per il funzionamento del sistema

- La classe predetta resta identica tra i due ambienti in tutti i test
  effettuati
- La confidenza resta comunque nello stesso ordine di grandezza (alta,
  non ambigua) in entrambi i casi
- Il sistema non basa nessuna decisione architetturale (es. offloading,
  validazione) sul valore esatto di confidenza, solo sulla classe predetta

## Rilevanza per la parte di adversarial robustness

Questo fenomeno è potenzialmente rilevante per gli esperimenti di
robustezza avversaria del tirocinio: un attacco calibrato su un ambiente
(es. Python/desktop) potrebbe comportarsi leggermente diversamente se
eseguito attraverso un ambiente di inferenza diverso (es. TFLite mobile).
