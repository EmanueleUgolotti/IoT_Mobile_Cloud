"""
Accesso al database. Semplice apposta: una funzione di connessione
e alcune funzioni di insert/update/select, nessun ORM.
"""

import json
import os
import psycopg2

from models import CapturedIngestRequest, ReferenceIngestRequest


def get_connection():
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "db"),
        port=os.environ.get("DB_PORT", "5432"),
        dbname=os.environ.get("DB_NAME", "bpa"),
        user=os.environ.get("DB_USER", "bpa"),
        password=os.environ.get("DB_PASSWORD", "bpa"),
    )


def insert_reference(payload: ReferenceIngestRequest) -> int:
    query = """
        INSERT INTO acquisitions
            (request_id, mode, node_id, seq_num, gateway_timestamp,
             image_ref, dataset_id, retry_attempt)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
        RETURNING id;
    """
    values = (
        payload.request_id,
        payload.mode,
        payload.node_id,
        payload.seq_num,
        payload.gateway_timestamp,
        payload.image_ref,
        payload.dataset_id,
        payload.retry_attempt,
    )
    return _execute_insert(query, values)


def insert_captured(payload: CapturedIngestRequest, image_path: str) -> int:
    query = """
        INSERT INTO acquisitions
            (request_id, mode, node_id, seq_num, gateway_timestamp,
             capture_id, image_path, retry_attempt)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
        RETURNING id;
    """
    values = (
        payload.request_id,
        payload.mode,
        payload.node_id,
        payload.seq_num,
        payload.gateway_timestamp,
        payload.capture_id,
        image_path,
        payload.retry_attempt,
    )
    return _execute_insert(query, values)


def insert_capture_failure(node_id: int, seq_num: int, reason: str) -> int:
    """
    Registra un tentativo di scatto fallito (segnalato dal Gateway):
    pezzi mancanti, CRC non valido, o inoltro fallito su entrambe le
    destinazioni. Non c'e' nessuna immagine associata (mai arrivata
    completa) - la riga serve solo a dire "qui doveva esserci uno
    scatto, ma non ce l'ha fatta", cosi' l'app puo' un giorno mostrare
    "scatto da rifare".
    """
    query = """
        INSERT INTO acquisitions
            (request_id, mode, node_id, seq_num, gateway_timestamp,
             capture_status, failure_reason, retry_attempt)
        VALUES (%s, 'captured', %s, %s, now(), 'failed', %s, 0)
        RETURNING id;
    """
    request_id = f"gw01-node{node_id}-fail{seq_num:06d}"
    values = (request_id, node_id, seq_num, reason)
    return _execute_insert(query, values)


def update_classification(acquisition_id: int, predicted_label: str, probabilities: dict) -> None:
    """
    Salva il risultato dell'inferenza ML per un'acquisizione gia'
    inserita. Chiamata separatamente dall'insert iniziale, perche' la
    classificazione avviene DOPO aver gia' salvato la richiesta - se la
    classificazione fallisce (es. servizio robustness non raggiungibile),
    la riga resta comunque salvata, solo senza risultato (colonne NULL).
    """
    query = """
        UPDATE acquisitions
        SET predicted_label = %s, probabilities = %s, classified_at = now()
        WHERE id = %s;
    """
    values = (predicted_label, json.dumps(probabilities), acquisition_id)

    conn = get_connection()
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute(query, values)
    finally:
        conn.close()


def get_recent_results(limit: int = 20) -> list[dict]:
    """
    Restituisce le acquisizioni gia' classificate (predicted_label non
    nullo), piu' recenti prima. Usata dall'endpoint GET /cloud/results.
    """
    query = """
        SELECT id, request_id, mode, image_ref, dataset_id, capture_id,
               predicted_label, probabilities, received_at, classified_at
        FROM acquisitions
        WHERE predicted_label IS NOT NULL
        ORDER BY received_at DESC
        LIMIT %s;
    """
    conn = get_connection()
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute(query, (limit,))
                columns = [desc[0] for desc in cur.description]
                rows = cur.fetchall()
                return [dict(zip(columns, row)) for row in rows]
    finally:
        conn.close()


def get_recent_failures(limit: int = 20) -> list[dict]:
    """
    Restituisce gli ultimi tentativi di scatto falliti (segnalati dal
    Gateway), piu' recenti prima.
    """
    query = """
        SELECT id, request_id, node_id, seq_num, failure_reason, received_at
        FROM acquisitions
        WHERE capture_status = 'failed'
        ORDER BY received_at DESC
        LIMIT %s;
    """
    conn = get_connection()
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute(query, (limit,))
                columns = [desc[0] for desc in cur.description]
                rows = cur.fetchall()
                return [dict(zip(columns, row)) for row in rows]
    finally:
        conn.close()


def _execute_insert(query: str, values: tuple) -> int:
    conn = get_connection()
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute(query, values)
                new_id = cur.fetchone()[0]
        return new_id
    finally:
        conn.close()