"""
Backend API - skeleton.
Espone /cloud/ingest, l'endpoint che il Gateway (ESP32 #2) chiama
per inoltrare un'acquisizione al cloud.

Dopo aver salvato la richiesta, prova a classificarla chiamando il
servizio robustness (che ha gia' il modello VGG16 caricato in memoria -
vedi decisions-log per il perche' non duplichiamo un secondo servizio
ML separato: eviterebbe di caricare TensorFlow due volte, costoso in
RAM). Se la classificazione fallisce (es. robustness non raggiungibile),
la richiesta resta comunque salvata correttamente - solo senza
risultato, recuperabile in un secondo momento se si rifa' girare
l'inferenza manualmente. La risposta al Gateway non dipende mai
dall'esito della classificazione.
"""

import base64
import os
import uuid
from pathlib import Path

import requests
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse

from pydantic import BaseModel, ValidationError

import db
from models import CapturedIngestRequest, IngestResponse, ReferenceIngestRequest

app = FastAPI(title="BPA Cloud Ingest API")

AUTH_ENABLED = os.environ.get("AUTH_ENABLED", "false").lower() == "true"
API_KEY = os.environ.get("API_KEY", "")

DATASET_PATH = Path(os.environ.get("DATASET_PATH", "/data/dataset"))
CAPTURED_IMAGES_PATH = Path(os.environ.get("CAPTURED_IMAGES_PATH", "/data/captured"))
CAPTURED_IMAGES_PATH.mkdir(parents=True, exist_ok=True)

# Indirizzo del servizio robustness (che ha gia' il modello VGG16
# caricato), raggiungibile tramite la rete Docker interna creata da
# docker-compose (nome del servizio = hostname, porta interna 8001 -
# stessa porta usata internamente da robustness-simba, per coerenza).
ROBUSTNESS_SERVICE_URL = os.environ.get("ROBUSTNESS_SERVICE_URL", "http://robustness:8001")


class CaptureFailureRequest(BaseModel):
    """
    Notifica dal Gateway: un tentativo di scatto non e' arrivato a buon
    fine (pezzi mancanti, CRC non valido, o inoltro fallito). Definito
    qui direttamente (non in models.py) per non rischiare di toccare
    quel file senza conoscerne il contenuto attuale.
    """
    node_id: int
    seq_num: int
    reason: str


def check_auth(x_api_key: str | None):
    if not AUTH_ENABLED:
        return
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


def classify_and_store(image_bytes: bytes, acquisition_id: int) -> None:
    """
    Chiama il servizio robustness per classificare l'immagine, e salva
    il risultato sulla riga gia' inserita. Non solleva mai eccezioni
    verso il chiamante - un fallimento qui non deve mai far fallire la
    risposta /cloud/ingest, che ha gia' fatto il suo lavoro principale
    (salvare la richiesta) prima di arrivare qui.
    """
    try:
        image_b64 = base64.b64encode(image_bytes).decode("utf-8")

        resp = requests.post(
            f"{ROBUSTNESS_SERVICE_URL}/robustness/predict",
            json={"images_data": [image_b64]},
            headers={"X-API-Key": API_KEY} if AUTH_ENABLED else {},
            timeout=30,
        )
        resp.raise_for_status()

        result = resp.json()
        class_names = result["class_names"]
        probabilities = result["probabilities"][0]  # unica immagine nel batch

        predicted_index = max(range(len(probabilities)), key=lambda i: probabilities[i])
        predicted_label = class_names[predicted_index]
        probabilities_dict = dict(zip(class_names, probabilities))

        db.update_classification(acquisition_id, predicted_label, probabilities_dict)

    except Exception as e:
        # Log semplice su stdout (visibile con "docker compose logs api") -
        # la richiesta resta comunque salvata senza risultato, vedi
        # commento della funzione.
        print(f"[classify_and_store] classificazione fallita per acquisizione "
              f"{acquisition_id}: {e}", flush=True)


@app.post("/cloud/ingest", response_model=IngestResponse)
async def ingest(request: Request, x_api_key: str | None = Header(default=None)):
    check_auth(x_api_key)

    body = await request.json()
    mode = body.get("mode")

    if mode == "reference":
        return _handle_reference(body)
    elif mode == "captured":
        return _handle_captured(body)
    else:
        raise HTTPException(status_code=400, detail=f"Unknown or missing mode: {mode!r}")


def _handle_reference(body: dict) -> IngestResponse:
    try:
        payload = ReferenceIngestRequest(**body)
    except ValidationError as e:
        raise HTTPException(status_code=422, detail=e.errors())

    image_path = DATASET_PATH / payload.dataset_id / payload.image_ref
    if not image_path.is_file():
        raise HTTPException(
            status_code=404,
            detail=f"image_ref '{payload.image_ref}' not found in dataset '{payload.dataset_id}'",
        )

    stored_id = db.insert_reference(payload)

    classify_and_store(image_path.read_bytes(), stored_id)

    return IngestResponse(status="ok", request_id=payload.request_id, stored_id=stored_id)


def _handle_captured(body: dict) -> IngestResponse:
    try:
        payload = CapturedIngestRequest(**body)
    except ValidationError as e:
        raise HTTPException(status_code=422, detail=e.errors())

    try:
        image_bytes = base64.b64decode(payload.image_data)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 image_data")

    filename = f"{payload.capture_id}_{uuid.uuid4().hex[:8]}.jpg"
    image_path = CAPTURED_IMAGES_PATH / filename
    image_path.write_bytes(image_bytes)

    stored_id = db.insert_captured(payload, str(image_path))

    classify_and_store(image_bytes, stored_id)

    return IngestResponse(status="ok", request_id=payload.request_id, stored_id=stored_id)


@app.get("/cloud/results")
async def get_results(limit: int = 20):
    """
    Restituisce le ultime acquisizioni gia' classificate dal cloud, piu'
    recenti prima. Pensato per essere interrogato dall'app mobile (o da
    qualunque altro client) per mostrare i risultati fatti lato server,
    non solo quelli fatti localmente sul telefono.
    """
    results = db.get_recent_results(limit=limit)
    # I timestamp (datetime) e il campo probabilities (gia' un dict
    # grazie a JSONB) sono serializzati automaticamente da FastAPI.
    return {"results": results}


@app.post("/cloud/capture-failed")
async def capture_failed(payload: CaptureFailureRequest, x_api_key: str | None = Header(default=None)):
    """
    Il Gateway chiama questo endpoint quando un tentativo di scatto non
    e' arrivato a buon fine (pezzi mancanti, CRC non valido, o inoltro
    fallito su entrambe le destinazioni) - salva solo un riferimento
    (nessuna immagine, non e' mai arrivata completa), cosi' in futuro
    l'app potra' mostrare "scatto da rifare".
    """
    check_auth(x_api_key)

    stored_id = db.insert_capture_failure(payload.node_id, payload.seq_num, payload.reason)
    return {"status": "ok", "stored_id": stored_id}


@app.get("/cloud/failures")
async def get_failures(limit: int = 20):
    """
    Restituisce gli ultimi tentativi di scatto falliti, piu' recenti
    prima - controparte di /cloud/results per i fallimenti.
    """
    failures = db.get_recent_failures(limit=limit)
    return {"failures": failures}


# Pagina minimale, senza framework ne' stile particolare - serve solo a
# dimostrare visivamente che il Cloud funziona, senza dover usare
# l'app o il terminale. Nessun requisito di grafica: tabelle semplici,
# JavaScript puro (fetch), nessuna libreria esterna.
DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="UTF-8">
<title>BPA Cloud - Dashboard</title>
<style>
    body { font-family: sans-serif; margin: 20px; }
    table { border-collapse: collapse; width: 100%; margin-bottom: 30px; }
    th, td { border: 1px solid #ccc; padding: 6px 10px; text-align: left; font-size: 14px; }
    th { background: #eee; }
    h1 { font-size: 20px; }
    h2 { font-size: 16px; margin-top: 30px; }
    .failed-row { background: #fdd; }
    button { padding: 6px 14px; margin-bottom: 15px; }
    #modal-overlay {
        display: none; position: fixed; top: 0; left: 0;
        width: 100%; height: 100%; background: rgba(0,0,0,0.7);
        align-items: center; justify-content: center;
    }
    #modal-box {
        background: #fff; padding: 20px; max-width: 600px;
        max-height: 90%; overflow: auto; border-radius: 4px;
    }
    #modal-image { max-width: 100%; display: block; margin-bottom: 15px; }
    #modal-probs { padding-left: 20px; }
    #modal-probs li.top-prediction { font-weight: bold; }
</style>
</head>
<body>

<h1>BPA Cloud - Dashboard</h1>
<button onclick="loadAll()">Aggiorna</button>

<h2>Classificazioni (/cloud/results)</h2>
<table id="results-table">
    <thead>
        <tr>
            <th>Immagine</th><th>ID</th><th>Request ID</th><th>Modalita'</th><th>Riferimento</th>
            <th>Classe</th><th>Ricevuto</th>
        </tr>
    </thead>
    <tbody></tbody>
</table>

<h2>Scatti falliti (/cloud/failures)</h2>
<table id="failures-table">
    <thead>
        <tr><th>ID</th><th>Request ID</th><th>Nodo</th><th>Seq</th><th>Motivo</th><th>Ricevuto</th></tr>
    </thead>
    <tbody></tbody>
</table>

<!-- Riquadro di dettaglio: immagine ingrandita + probabilita' complete
     per ogni classe - si apre cliccando sulla miniatura di una riga. -->
<div id="modal-overlay" onclick="if (event.target === this) closeModal()">
    <div id="modal-box">
        <button onclick="closeModal()">Chiudi</button>
        <img id="modal-image" src="" alt="Immagine ingrandita">
        <h3>Probabilita' per classe</h3>
        <ul id="modal-probs"></ul>
    </div>
</div>

<script>
let lastResults = [];

async function loadResults() {
    const res = await fetch('/cloud/results?limit=50');
    const data = await res.json();
    lastResults = data.results;

    const tbody = document.querySelector('#results-table tbody');
    tbody.innerHTML = '';
    for (const r of data.results) {
        const ref = r.image_ref || r.capture_id || '-';
        const tr = document.createElement('tr');
        tr.innerHTML = `<td><img src="/cloud/results/${r.id}/image" style="max-width:70px;max-height:70px;cursor:pointer;" onclick="showDetails(${r.id})" onerror="this.style.display='none'"></td>
                        <td>${r.id}</td><td>${r.request_id}</td><td>${r.mode}</td>
                        <td>${ref}</td><td>${r.predicted_label || '-'}</td>
                        <td>${r.received_at || '-'}</td>`;
        tbody.appendChild(tr);
    }
}

function showDetails(id) {
    const r = lastResults.find(x => x.id === id);
    if (!r) return;

    document.getElementById('modal-image').src = `/cloud/results/${id}/image`;

    const probsList = document.getElementById('modal-probs');
    probsList.innerHTML = '';
    if (r.probabilities) {
        const entries = Object.entries(r.probabilities).sort((a, b) => b[1] - a[1]);
        for (const [label, prob] of entries) {
            const li = document.createElement('li');
            const pct = (prob * 100).toFixed(1);
            li.textContent = `${label}: ${pct}%`;
            if (label === r.predicted_label) {
                li.className = 'top-prediction';
            }
            probsList.appendChild(li);
        }
    } else {
        probsList.innerHTML = "<li>Nessuna probabilita' disponibile</li>";
    }

    document.getElementById('modal-overlay').style.display = 'flex';
}

function closeModal() {
    document.getElementById('modal-overlay').style.display = 'none';
}

async function loadFailures() {
    const res = await fetch('/cloud/failures?limit=50');
    const data = await res.json();
    const tbody = document.querySelector('#failures-table tbody');
    tbody.innerHTML = '';
    for (const f of data.failures) {
        const tr = document.createElement('tr');
        tr.className = 'failed-row';
        tr.innerHTML = `<td>${f.id}</td><td>${f.request_id}</td><td>${f.node_id}</td>
                        <td>${f.seq_num}</td><td>${f.failure_reason}</td>
                        <td>${f.received_at || '-'}</td>`;
        tbody.appendChild(tr);
    }
}

function loadAll() {
    loadResults();
    loadFailures();
}

loadAll();
</script>

</body>
</html>
"""


@app.get("/dashboard", response_class=HTMLResponse)
async def dashboard():
    """
    Pagina web minimale che mostra i dati del Cloud (classificazioni e
    scatti falliti) senza bisogno del telefono - serve solo a
    dimostrare visivamente che il sistema funziona. Riusa gli stessi
    endpoint gia' esistenti (/cloud/results, /cloud/failures) tramite
    JavaScript nel browser, nessuna nuova logica sul backend.
    """
    return HTMLResponse(content=DASHBOARD_HTML)


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/cloud/results/{acquisition_id}/image")
async def get_result_image(acquisition_id: int):
    """
    Serve l'immagine associata a una riga di /cloud/results, sia che
    sia una foto vera catturata (gia' salvata su disco, percorso in
    image_path) sia un riferimento al dataset condiviso (stesso file
    usato anche dall'app) - un solo endpoint per entrambi i casi, cosi'
    la dashboard puo' mostrare una miniatura senza doversi preoccupare
    di quale dei due tipi sia.
    """
    row = db.get_acquisition_by_id(acquisition_id)
    if row is None:
        raise HTTPException(status_code=404, detail=f"Acquisition {acquisition_id} not found")

    if row["mode"] == "captured":
        if not row["image_path"]:
            raise HTTPException(status_code=404, detail="No image stored for this capture")
        image_path = Path(row["image_path"])
    else:  # reference
        image_path = DATASET_PATH / row["dataset_id"] / row["image_ref"]

    if not image_path.is_file():
        raise HTTPException(status_code=404, detail="Image file not found on disk")

    return FileResponse(image_path)


@app.get("/dataset/image")
async def get_dataset_image(dataset_id: str, image_ref: str):
    """
    Serve il file immagine vero, dato il riferimento e il dataset.
    Usato dall'app mobile per scaricare l'immagine quando riceve una
    richiesta in modalita' 'reference' dal Gateway (che manda solo il
    nome del file, non i byte).
    """
    image_path = DATASET_PATH / dataset_id / image_ref
    if not image_path.is_file():
        raise HTTPException(
            status_code=404,
            detail=f"image_ref '{image_ref}' not found in dataset '{dataset_id}'",
        )
    return FileResponse(image_path)