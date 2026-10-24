# BPA RMI Cluster

## Compilare
    mkdir out && javac --release 17 -d out src/bpa/*.java

## Avviare (un terminale ciascuno, con Docker gia' attivo: robustness su localhost:8001)
    java -cp out bpa.Main 1 3
    java -cp out bpa.Main 2 3
    java -cp out bpa.Main 3 3
    java -cp out bpa.Bridge 3 9000

## Provare senza backend
    curl -X POST --data-binary @foto.jpg "localhost:9000/submit?id=prova1"
    curl localhost:9000/status
    curl "localhost:9000/get?id=prova1"

## Demo: chiudere il terminale del nodo leader (di solito il 3) e rifare submit/status:
rielezione automatica, immagini ancora leggibili. Riavviare il nodo: si risincronizza.

Variabili: ROBUSTNESS_URL (default http://localhost:8001), API_KEY (se AUTH_ENABLED=true).
Integrazione col backend: vedi backend_snippet.py
