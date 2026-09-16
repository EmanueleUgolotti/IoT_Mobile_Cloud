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
from fastapi.responses import FileResponse

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


@app.get("/health")
async def health():
    return {"status": "ok"}


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