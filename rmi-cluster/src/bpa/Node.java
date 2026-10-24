package bpa;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Nodo del cluster P2P: heartbeat, elezione Bully, replicazione, orologio di Lamport. */
public class Node implements NodeService {
    static final int BASE_PORT = 1100;
    static final int HEARTBEAT_MS = 1000;
    static final int RMI_TIMEOUT_MS = 800;

    final int id;
    final List<Integer> peerIds;
    final Map<String, ImageRecord> store = new ConcurrentHashMap<>();
    final AtomicLong lamport = new AtomicLong();
    volatile int leaderId = -1;
    volatile boolean electing = false;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2);

    public Node(int id, List<Integer> allIds) {
        this.id = id;
        this.peerIds = new ArrayList<>(allIds);
        this.peerIds.remove(Integer.valueOf(id));
    }

    void log(String m) { System.out.printf("[node%d L%d] %s%n", id, lamport.get(), m); }

    void start() throws Exception {
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", String.valueOf(RMI_TIMEOUT_MS));
        System.setProperty("sun.rmi.transport.tcp.readTimeout", String.valueOf(RMI_TIMEOUT_MS));
        System.setProperty("sun.rmi.transport.connectionTimeout", String.valueOf(RMI_TIMEOUT_MS));
        NodeService stub = (NodeService) UnicastRemoteObject.exportObject(this, BASE_PORT + 100 + id);
        Registry reg = LocateRegistry.createRegistry(BASE_PORT + id);
        reg.rebind("node", stub);
        log("avviato, registry sulla porta " + (BASE_PORT + id));
        sched.schedule(this::syncFromPeers, 1, TimeUnit.SECONDS);
        sched.schedule(this::startElection, 1500, TimeUnit.MILLISECONDS);
        sched.scheduleWithFixedDelay(this::heartbeat, 3, HEARTBEAT_MS / 1000, TimeUnit.SECONDS);
    }

    NodeService peer(int pid) {
        try {
            Registry r = LocateRegistry.getRegistry("localhost", BASE_PORT + pid);
            return (NodeService) r.lookup("node");
        } catch (Exception e) { return null; }
    }

    // ---------- Failure detection ----------
    void heartbeat() {
        if (leaderId == id || leaderId < 0 || electing) return;
        NodeService l = peer(leaderId);
        try {
            if (l == null || !l.ping(id)) throw new RemoteException("no ping");
        } catch (Exception e) {
            log("leader node" + leaderId + " NON risponde -> avvio elezione");
            startElection();
        }
    }

    // ---------- Bully ----------
    synchronized void startElection() {
        if (electing) return;
        electing = true;
        try {
            boolean higherAlive = false;
            for (int p : peerIds) {
                if (p > id) {
                    NodeService n = peer(p);
                    try { if (n != null && n.election(id)) higherAlive = true; } catch (Exception ignored) {}
                }
            }
            if (!higherAlive) {
                leaderId = id;
                log("*** divento LEADER ***");
                for (int p : peerIds) {
                    NodeService n = peer(p);
                    try { if (n != null) n.coordinator(id); } catch (Exception ignored) {}
                }
            } else {
                // un nodo piu' alto e' vivo e condurra' l'elezione: attendo l'annuncio
                sched.schedule(() -> { if (leaderId < 0 || !alive(leaderId)) { electing = false; startElection(); } },
                        3, TimeUnit.SECONDS);
            }
        } finally { if (leaderId >= 0) electing = false; }
    }

    boolean alive(int pid) {
        NodeService n = peer(pid);
        try { return n != null && n.ping(id); } catch (Exception e) { return false; }
    }

    @Override public boolean ping(int fromId) { return true; }

    @Override public boolean election(int fromId) {
        log("elezione richiesta da node" + fromId + " -> rispondo e rilancio");
        sched.execute(this::startElection);
        return true;
    }

    @Override public void coordinator(int leader) {
        leaderId = leader; electing = false;
        log("nuovo coordinatore: node" + leader);
    }

    // ---------- Lavoro applicativo ----------
    @Override public String submit(String imageId, byte[] data) throws RemoteException {
        long t = lamport.incrementAndGet();
        if (leaderId != id) {
            NodeService l = leaderId >= 0 ? peer(leaderId) : null;
            if (l == null) throw new RemoteException("nessun leader disponibile, riprova");
            return l.submit(imageId, data);
        }
        // sono il leader: classifico e replico
        Classifier.Result r = Classifier.classify(data);
        ImageRecord rec = new ImageRecord(imageId, data, r.label, r.confidence, id, t);
        store.put(imageId, rec);
        int ok = 1;
        for (int p : peerIds) {
            NodeService n = peer(p);
            try { if (n != null) { n.replicate(rec); ok++; } } catch (Exception e) { log("replica su node" + p + " fallita"); }
        }
        log("classificata " + rec + " replicata su " + ok + " nodi");
        return rec.toString();
    }

    @Override public void replicate(ImageRecord rec) {
        lamport.updateAndGet(x -> Math.max(x, rec.lamport) + 1);
        store.put(rec.id, rec);
        log("replica ricevuta: " + rec);
    }

    @Override public ImageRecord get(String imageId) { return store.get(imageId); }
    @Override public List<String> listIds() { return new ArrayList<>(store.keySet()); }

    @Override public String status() {
        return "node" + id + " leader=node" + leaderId + " immagini=" + store.keySet() + " L=" + lamport.get();
    }

    /** All'avvio (o al rientro) si copiano i record mancanti da un peer. */
    void syncFromPeers() {
        for (int p : peerIds) {
            NodeService n = peer(p);
            if (n == null) continue;
            try {
                for (String rid : n.listIds()) {
                    if (!store.containsKey(rid)) { store.put(rid, n.get(rid)); log("risincronizzato " + rid + " da node" + p); }
                }
            } catch (Exception ignored) {}
        }
    }
}
