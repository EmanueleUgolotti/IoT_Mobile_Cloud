# Modelli dati per i payload ricevuti dal gateway (ESP32 #2).
# Rispecchiano esattamente lo schema definito in
# docs/architecture/network-design.md (sezione 4).


from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field


class BaseIngestRequest(BaseModel):
    request_id: str
    node_id: int
    seq_num: int
    gateway_timestamp: datetime
    retry_attempt: int = 0


class ReferenceIngestRequest(BaseIngestRequest):
    mode: Literal["reference"]
    image_ref: str
    dataset_id: str


class CapturedIngestRequest(BaseIngestRequest):
    mode: Literal["captured"]
    capture_id: str
    image_data: str


class IngestResponse(BaseModel):
    status: str
    request_id: str
    stored_id: Optional[int] = None