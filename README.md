# Bloodstain Pattern Analysis — Distributed Edge-Cloud System

Sistema distribuito per l'acquisizione, la classificazione e l'analisi di robustezza avversaria di pattern di macchie di sangue (Bloodstain Pattern Analysis, BPA), progettato come integrazione tra dispositivi edge (ESP32), un'applicazione mobile Android con inferenza locale, e un backend cloud containerizzato.

Il progetto integra il lavoro di tirocinio svolto su Machine Learning applicato alla classificazione di bloodstain pattern e su adversarial robustness.

## Architettura concettuale

```
ESP32 #1 (Acquisition Node)
        |
        v
ESP32 #2 (Gateway)
        |
        v
   Decisione di offloading
        |
   +----+----+
   |         |
Mobile      Cloud
   |         |
   +----+----+
        |
        v
   ML Inference
        |
        v
    Risultati
        |
        v
    Database
```

## Struttura del repository

```
IoT_mobile_cloud/
│
├── README.md
├── .gitignore
│
├── docs/
│   ├── architecture/     # decisioni architetturali, ADR
│   ├── requirements/     # requisiti funzionali e non funzionali
│   └── diagrams/         # diagrammi di sistema
│
├── esp32/
│   ├── acquisition-node/ # firmware ESP32 #1 (simulazione acquisizione immagini)
│   └── gateway/          # firmware ESP32 #2 (ricezione dati, offloading)
│
├── android/               # app Android (preprocessing + inferenza TFLite locale)
│
├── backend/
│   ├── api/               # API di ricezione dati / orchestrazione
│   ├── inference/         # servizio ML per inferenza remota
│   ├── robustness/        # analisi di adversarial robustness (opzionale)
│   └── database/          # schema e accesso al database
│
├── models/                 # modelli ML (classico + VGG16) provenienti dal tirocinio
│
├── docker/                 # Dockerfile, docker-compose, configurazioni Swarm
│
├── experiments/             # script ed esperimenti (es. attacchi avversari)
│
└── tests/                   # test automatici
```

## Componenti principali

| Componente          | Ruolo                                                             |
|---------------------|--------------------------------------------------------------------|
| ESP32 #1             | Simula la raccolta di immagini di bloodstain pattern              |
| ESP32 #2             | Gateway: riceve i dati e gestisce la decisione di offloading      |
| App Android          | Preprocessing e inferenza locale via TensorFlow Lite              |
| Backend cloud        | Containerizzato con Docker, espone API per ricezione/orchestrazione |
| Servizio ML          | Inferenza remota (modelli derivati dal tirocinio)                 |
| Database             | Memorizza risultati e metadati delle classificazioni              |
| Adversarial module   | (Opzionale) Analisi di robustezza avversaria sui modelli           |

## Deployment

- **Docker Compose**: modalità principale di deployment per lo sviluppo e la demo del sistema.
- **Docker Swarm**: estensione avanzata opzionale per orchestrazione multi-nodo.

## Stato del progetto

Progetto in sviluppo incrementale, un task alla volta. Vedi `docs/` per le decisioni
architetturali via via documentate.
