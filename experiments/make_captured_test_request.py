"""
Genera un file JSON di payload e il comando curl per testare l'endpoint
/mobile/ingest in modalita' 'captured': legge un'immagine reale, la
codifica in base64, e salva il payload completo su file (il comando da
incollare a mano diventerebbe enorme e scomodo).

Simula quello che il Gateway (ESP32 #2) manderebbe al telefono quando
riceve un'immagine scattata dalla webcam dell'ESP32-CAM (nodo #1).

Esecuzione:
    python make_captured_test_request.py --image /percorso/a/immagine.jpg
"""

import argparse
import base64
import json


def main():
    parser = argparse.ArgumentParser(
        description="Genera un payload JSON e comando curl per /mobile/ingest (captured)"
    )
    parser.add_argument("--image", required=True, help="Percorso a un'immagine di test")
    parser.add_argument("--port", default=8080, help="Porta del server sul telefono (default 8080)")
    parser.add_argument("--capture-id", default="cap-test-001", help="Identificativo della cattura")
    parser.add_argument("--output", default="captured_payload.json", help="File JSON di output")
    args = parser.parse_args()

    with open(args.image, "rb") as f:
        image_bytes = f.read()

    image_b64 = base64.b64encode(image_bytes).decode("utf-8")

    payload = {
        "request_id": "gw01-000099",
        "mode": "captured",
        "node_id": 1,
        "seq_num": 99,
        "gateway_timestamp": "2026-08-19T10:00:00Z",
        "capture_id": args.capture_id,
        "image_data": image_b64,
        "retry_attempt": 0,
    }

    with open(args.output, "w") as f:
        json.dump(payload, f)

    print(f"Payload salvato in: {args.output} ({len(image_b64)} caratteri base64)\n")
    print("Comando curl da eseguire:\n")
    print(f"curl -X POST http://localhost:{args.port}/mobile/ingest \\")
    print("  -H \"Content-Type: application/json\" \\")
    print(f"  -d @{args.output}")


if __name__ == "__main__":
    main()