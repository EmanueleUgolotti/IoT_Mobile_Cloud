"""
Endpoint HTTP per l'esecuzione di attacchi adversarial sul modello VGG16.

Questo servizio gestisce direttamente gli attacchi basati su ART
(Boundary, HSJ, ZOO), che usano solo TensorFlow. L'attacco SimBA
(basato su PyTorch) viene invece INOLTRATO al servizio separato
robustness-simba: TensorFlow e PyTorch importati nello stesso processo
causano un crash (segmentation fault) dentro Docker, verificato
empiricamente - vedi decisions-log per i dettagli della diagnosi.
Questo servizio non importa mai PyTorch, quindi il conflitto non puo'
piu' verificarsi.

Espone anche /robustness/predict, un endpoint "interno" (nel senso che
serve al servizio robustness-simba, non pensato per l'app) che risponde
solo con le probabilita' di classificazione, senza eseguire nessun
attacco - usato da SimBA per interrogare il modello mentre esplora le
perturbazioni, senza dover caricare TensorFlow nel proprio container.

Il modello viene caricato una sola volta all'avvio del servizio.
"""

import base64
import os
from io import BytesIO

import numpy as np
import requests
from fastapi import FastAPI, Header, HTTPException
from PIL import Image
from pydantic import BaseModel

import pipeline_predict
from run_hsj_zoo_boundary_vgg16 import run_art_attack, ATTACK_DEFAULTS

app = FastAPI(title="BPA Adversarial Robustness API")

AUTH_ENABLED = os.environ.get("AUTH_ENABLED", "false").lower() == "true"
API_KEY = os.environ.get("API_KEY", "")

MODEL_PATH = os.environ.get("MODEL_PATH", "models/best_model.keras")  # relativo a /app (WORKDIR del container)

# Indirizzo del servizio SimBA (PyTorch), raggiungibile tramite la rete
# Docker interna creata da docker-compose (nome del servizio = hostname).
SIMBA_SERVICE_URL = os.environ.get("SIMBA_SERVICE_URL", "http://robustness-simba:8001")

ART_ATTACKS = {"hsj": "HSJ", "zoo": "ZOO", "boundary": "Boundary"}
SIMBA_ATTACKS = {"simba"}

_model = None
_class_names = None


@app.on_event("startup")
def load_model_on_startup():
    global _model, _class_names
    _model = pipeline_predict.load_model("vgg16", MODEL_PATH)
    _class_names = pipeline_predict.get_class_names("vgg16", _model)

    # "Riscaldamento": la prima vera predizione dopo il caricamento e'
    # spesso molto piu' lenta delle successive (ottimizzazione interna
    # di TensorFlow alla prima chiamata) - la facciamo qui, all'avvio,
    # cosi' il rallentamento non capita durante una richiesta vera
    # (causava un timeout nel servizio robustness-simba, che aspetta
    # una risposta entro un tempo limitato).
    dummy_image = np.zeros((224, 224, 3), dtype=np.float32)
    pipeline_predict.predict_proba_batch(dummy_image[np.newaxis, ...], "vgg16", _model)


class AttackRequest(BaseModel):
    image_data: str  # immagine JPEG/PNG codificata in base64
    attack: str  # "boundary" | "hsj" | "zoo" | "simba"
    max_iter: int | None = None  # override opzionale del budget di query


class AttackResponse(BaseModel):
    status: str
    attack: str
    prediction_original: str
    probabilities_original: dict
    prediction_adversarial: str
    probabilities_adversarial: dict
    success: bool
    adversarial_image_data: str  # PNG codificata in base64


class PredictRequest(BaseModel):
    images_data: list[str]  # batch di immagini, ciascuna JPEG/PNG base64


class PredictResponse(BaseModel):
    class_names: list[str]
    probabilities: list[list[float]]  # una lista di probabilita' per immagine, stesso ordine di class_names


def check_auth(x_api_key: str | None):
    if not AUTH_ENABLED:
        return
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


def decode_image(image_data_b64: str) -> np.ndarray:
    try:
        image_bytes = base64.b64decode(image_data_b64)
        img = Image.open(BytesIO(image_bytes)).convert("RGB")
        return np.array(img, dtype=np.uint8)
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid base64 image_data")


def encode_image(img_rgb_uint8: np.ndarray) -> str:
    img = Image.fromarray(img_rgb_uint8, mode="RGB")
    buffer = BytesIO()
    img.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("utf-8")


@app.post("/robustness/attack", response_model=AttackResponse)
def attack(request: AttackRequest, x_api_key: str | None = Header(default=None)):
    # Funzione sincrona (non "async def") apposta: FastAPI la esegue
    # automaticamente in un thread separato, cosi' un attacco lungo (anche
    # minuti) non blocca l'intero servizio - richieste concorrenti (es.
    # /health, o un'altra /robustness/predict da SimBA) possono essere
    # gestite nel frattempo, invece di restare "invisibilmente" in coda.
    check_auth(x_api_key)

    attack_name_lower = request.attack.lower()

    if attack_name_lower in ART_ATTACKS:
        img = decode_image(request.image_data)
        result = run_art_attack(
            img,
            ART_ATTACKS[attack_name_lower],
            model=_model,
            class_names=_class_names,
            max_iter=request.max_iter,
        )
        return AttackResponse(
            status="ok",
            attack=request.attack,
            prediction_original=result["prediction_original"],
            probabilities_original=result["probabilities_original"],
            prediction_adversarial=result["prediction_adversarial"],
            probabilities_adversarial=result["probabilities_adversarial"],
            success=result["success"],
            adversarial_image_data=encode_image(result["adversarial_image_rgb"]),
        )

    elif attack_name_lower in SIMBA_ATTACKS:
        # Inoltro al servizio separato (PyTorch) - vedi nota in cima al file
        try:
            resp = requests.post(
                f"{SIMBA_SERVICE_URL}/robustness/attack",
                json=request.model_dump(),
                headers={"X-API-Key": API_KEY} if AUTH_ENABLED else {},
                timeout=600,  # gli attacchi possono richiedere diversi minuti
            )
        except requests.exceptions.RequestException as e:
            raise HTTPException(
                status_code=503,
                detail=f"Servizio SimBA non raggiungibile: {e}",
            )
        if resp.status_code != 200:
            raise HTTPException(status_code=resp.status_code, detail=resp.text)
        return AttackResponse(**resp.json())

    else:
        valid_names = sorted(ART_ATTACKS.keys() | SIMBA_ATTACKS)
        raise HTTPException(
            status_code=400,
            detail=f"Attacco non riconosciuto: '{request.attack}'. Validi: {valid_names}",
        )


@app.post("/robustness/predict", response_model=PredictResponse)
async def predict(request: PredictRequest, x_api_key: str | None = Header(default=None)):
    """
    Endpoint interno: risponde solo con le probabilita' di classificazione
    per un batch di immagini, senza eseguire nessun attacco. Usato dal
    servizio robustness-simba per interrogare il modello VGG16 senza
    dover caricare TensorFlow nel proprio container.

    NOTA: e' rimasto "async def" (a differenza di /robustness/attack sopra)
    perche' e' rapido (una singola inferenza, <1s) - non ha bisogno del
    thread pool per non bloccare il servizio.
    """
    import time
    t0 = time.time()
    print(f"[/robustness/predict] richiesta ricevuta, {len(request.images_data)} immagini", flush=True)

    check_auth(x_api_key)

    images = [decode_image(img_b64) for img_b64 in request.images_data]
    print(f"[/robustness/predict] immagini decodificate ({time.time()-t0:.2f}s), "
          f"shape[0]={images[0].shape}", flush=True)

    batch = np.stack(images, axis=0).astype(np.float32)

    probs_batch = pipeline_predict.predict_proba_batch(batch, "vgg16", _model)
    print(f"[/robustness/predict] predizione completata ({time.time()-t0:.2f}s totali)", flush=True)

    return PredictResponse(
        class_names=_class_names,
        probabilities=probs_batch.tolist(),
    )


@app.get("/health")
async def health():
    return {"status": "ok", "model_loaded": _model is not None}