"""
Backend API - skeleton.
Espone /cloud/ingest, l'endpoint che il Gateway (ESP32 #2) chiama
per inoltrare un'acquisizione al cloud.
"""

import base64
import os
import uuid
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import ValidationError

import db
from models import CapturedIngestRequest, IngestResponse, ReferenceIngestRequest

app = FastAPI(title="BPA Cloud Ingest API")

AUTH_ENABLED = os.environ.get("AUTH_ENABLED", "false").lower() == "true"
API_KEY = os.environ.get("API_KEY", "")

DATASET_PATH = Path(os.environ.get("DATASET_PATH", "/data/dataset"))
CAPTURED_IMAGES_PATH = Path(os.environ.get("CAPTURED_IMAGES_PATH", "/data/captured"))
CAPTURED_IMAGES_PATH.mkdir(parents=True, exist_ok=True)


def check_auth(x_api_key: str | None):
    if not AUTH_ENABLED:
        return
    if x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


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
    return IngestResponse(status="ok", request_id=payload.request_id, stored_id=stored_id)


@app.get("/health")
async def health():
    return {"status": "ok"}