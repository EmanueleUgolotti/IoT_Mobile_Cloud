# ===== backend/api/main.py : AGGIUNTE =====

# 1) In alto, accanto agli altri os.environ.get(...) e agli import (serve "import threading"):
import threading
RMI_BRIDGE_URL = os.environ.get("RMI_BRIDGE_URL", "")   # vuoto = cluster disattivato


# 2) Una nuova funzione, prima di classify_and_store:
def forward_to_cluster(image_bytes: bytes, acquisition_id: int) -> None:
    """Inoltra l'immagine al cluster RMI (via bridge). Mai bloccante, mai fatale."""
    if not RMI_BRIDGE_URL:
        return
    try:
        r = requests.post(f"{RMI_BRIDGE_URL}/submit",
                          params={"id": f"acq-{acquisition_id}"},
                          data=image_bytes, timeout=40)
        print(f"[cluster] acq-{acquisition_id}: {r.status_code} {r.text}")
    except Exception as e:
        print(f"[cluster] inoltro fallito per acq-{acquisition_id}: {e}")


# 3) Come PRIMA riga dentro classify_and_store(image_bytes, acquisition_id):
    threading.Thread(target=forward_to_cluster,
                     args=(image_bytes, acquisition_id), daemon=True).start()


# 4) Un nuovo endpoint, per mostrare lo stato del cluster dal backend/dashboard:
@app.get("/cloud/cluster")
def cluster_status():
    if not RMI_BRIDGE_URL:
        return {"enabled": False, "nodes": []}
    try:
        r = requests.get(f"{RMI_BRIDGE_URL}/status", timeout=5)
        return {"enabled": True, "nodes": r.text.strip().splitlines()}
    except Exception as e:
        return {"enabled": True, "error": str(e), "nodes": []}

# ===== docker-compose.yml, servizio "api" : AGGIUNGERE =====
#   environment:
#     RMI_BRIDGE_URL: http://host.docker.internal:9000
#   extra_hosts:
#     - "host.docker.internal:host-gateway"
