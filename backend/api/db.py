"""
Accesso al database. Semplice apposta: una funzione di connessione
e una funzione di insert, nessun ORM.
"""

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