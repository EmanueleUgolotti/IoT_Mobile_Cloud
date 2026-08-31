"""
Endpoint HTTP per l'attacco SimBA, in un servizio Docker separato da
quello TensorFlow (backend/robustness/), per evitare il conflitto
TensorFlow+PyTorch nello stesso processo (vedi decisions-log.md per la
diagnosi completa - crash confermato empiricamente).

Questo servizio NON carica il modello VGG16 direttamente: ad ogni
"domanda" di cui SimBA ha bisogno durante l'attacco, chiama il servizio
TensorFlow (backend/robustness/, endpoint /robustness/predict) via HTTP.
Più lento di una chiamata locale, ma architetturalmente pulito - nessun
conflitto di librerie possibile, dato che qui PyTorch non incontra mai
TensorFlow.
"""

import base64
import os
from io import BytesIO

import numpy as np
import requests
from fastapi import FastAPI, Header, HTTPException
from PIL import Image
from pydantic import BaseModel

from run_simba import run_simba_attack

app = FastAPI(title="BPA SimBA Attack Service")

AUTH_ENABLED = os.environ.get("AUTH_ENABLED", "false").lower() == "true"
API_KEY = os.environ.get("API_KEY", "")

# Indirizzo del servizio TensorFlow, raggiungibile tramite la rete
# Docker interna creata da docker-compose (nome del servizio = hostname).
TF_SERVICE_URL = os.environ.get("TF_SERVICE_URL", "http://robustness:8001")

_class_names_cache = None  # ottenuti alla prima richiesta, poi riusati


class AttackRequest(BaseModel):
    image_data: str
    attack: str  # atteso "simba", ma non validato qui (il servizio TF fa da dispatcher)
    max_iter: int | None = None


class AttackResponse(BaseModel):
    status: str
    attack: str
    prediction_original: str
    probabilities_original: dict
    prediction_adversarial: str
    probabilities_adversarial: dict
    success: bool
    adversarial_image_data: str


def check_auth(x_api_key: str | None):
    if not AUTH_ENABLED:
        return
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


def _to_plain_floats(prob_dict: dict) -> dict:
    """
    Converte un dizionario {classe: numpy.float32} in {classe: float}
    "normale" - Pydantic non sa serializzare direttamente i tipi numpy
    in JSON (causava PydanticSerializationError).
    """
    return {k: float(v) for k, v in prob_dict.items()}


def encode_image_b64(img_rgb_uint8: np.ndarray) -> str:
    img = Image.fromarray(img_rgb_uint8, mode="RGB")
    buffer = BytesIO()
    img.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("utf-8")


def make_remote_predict_fn():
    """
    Costruisce una predict_fn che manda un batch di immagini al servizio
    TensorFlow via HTTP e restituisce le probabilita'. Popola anche la
    cache dei nomi delle classi al primo utilizzo.
    """
    call_counter = {"n": 0}

    def predict_fn(img_rgb_255_batch: np.ndarray) -> np.ndarray:
        global _class_names_cache

        call_counter["n"] += 1
        call_id = call_counter["n"]
        batch_size = img_rgb_255_batch.shape[0]
        print(f"[predict_fn #{call_id}] inizio, batch_size={batch_size}, "
              f"shape={img_rgb_255_batch.shape}, dtype={img_rgb_255_batch.dtype}, "
              f"min={img_rgb_255_batch.min():.2f}, max={img_rgb_255_batch.max():.2f}",
              flush=True)

        images_b64 = []
        for img in img_rgb_255_batch:
            img_uint8 = np.clip(img, 0, 255).astype(np.uint8)
            images_b64.append(encode_image_b64(img_uint8))

        payload_size_kb = sum(len(s) for s in images_b64) / 1024
        print(f"[predict_fn #{call_id}] payload codificato: {payload_size_kb:.1f} KB, "
              f"invio richiesta a {TF_SERVICE_URL}...", flush=True)

        try:
            resp = requests.post(
                f"{TF_SERVICE_URL}/robustness/predict",
                json={"images_data": images_b64},
                headers={"X-API-Key": API_KEY} if AUTH_ENABLED else {},
                timeout=90,  # la prima predizione dopo l'avvio di TensorFlow
                             # puo' essere molto piu' lenta delle successive
            )
            resp.raise_for_status()
        except requests.exceptions.RequestException as e:
            print(f"[predict_fn #{call_id}] ERRORE nella richiesta: {e}", flush=True)
            raise RuntimeError(f"Servizio TensorFlow non raggiungibile: {e}")

        print(f"[predict_fn #{call_id}] risposta ricevuta, status {resp.status_code}", flush=True)

        data = resp.json()
        if _class_names_cache is None:
            _class_names_cache = data["class_names"]

        return np.array(data["probabilities"], dtype=np.float32)

    return predict_fn


@app.post("/robustness/attack", response_model=AttackResponse)
def attack(request: AttackRequest, x_api_key: str | None = Header(default=None)):
    # Sincrona apposta (vedi stessa nota in robustness/main.py): un attacco
    # SimBA puo' richiedere minuti; eseguirla in un thread separato evita
    # di bloccare l'intero servizio (es. /health) per tutta la durata.
    check_auth(x_api_key)

    try:
        image_bytes = base64.b64decode(request.image_data)
        img = Image.open(BytesIO(image_bytes)).convert("RGB")
        img_rgb_uint8 = np.array(img, dtype=np.uint8)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 image_data")

    predict_fn = make_remote_predict_fn()

    # Chiamata "a vuoto" per popolare la cache dei nomi classe prima
    # dell'attacco vero e proprio (serve conoscerli per interpretare i
    # risultati)
    if _class_names_cache is None:
        predict_fn(img_rgb_uint8[np.newaxis, ...].astype(np.float32))

    kwargs = {}
    if request.max_iter is not None:
        kwargs["max_iters"] = request.max_iter

    try:
        result = run_simba_attack(
            img_rgb_uint8, _class_names_cache, predict_fn=predict_fn, **kwargs
        )
    except RuntimeError as e:
        raise HTTPException(status_code=503, detail=str(e))

    return AttackResponse(
        status="ok",
        attack="simba",
        prediction_original=result["prediction_original"],
        probabilities_original=_to_plain_floats(result["probabilities_original"]),
        prediction_adversarial=result["prediction_adversarial"],
        probabilities_adversarial=_to_plain_floats(result["probabilities_adversarial"]),
        success=result["success"],
        adversarial_image_data=encode_image_b64(result["adversarial_image_rgb"]),
    )


@app.get("/health")
async def health():
    return {"status": "ok"}