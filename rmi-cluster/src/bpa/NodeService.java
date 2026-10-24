package bpa;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/** Interfaccia remota di un nodo del cluster: ogni nodo e' sia client sia server. */
public interface NodeService extends Remote {
    /** Heartbeat: restituisce true se il nodo e' vivo. */
    boolean ping(int fromId) throws RemoteException;

    /** Bully: un nodo con ID minore chiede l'elezione. Risponde true ("ci penso io"). */
    boolean election(int fromId) throws RemoteException;

    /** Bully: annuncio del nuovo coordinatore. */
    void coordinator(int leaderId) throws RemoteException;

    /** Sottomette un'immagine al cluster (il nodo contattato la inoltra al leader). */
    String submit(String imageId, byte[] data) throws RemoteException;

    /** Replica di un record (chiamata dal leader verso i follower). */
    void replicate(ImageRecord rec) throws RemoteException;

    /** Lettura di un record dalla copia locale. */
    ImageRecord get(String imageId) throws RemoteException;

    /** Elenco id presenti localmente (usato per la risincronizzazione). */
    List<String> listIds() throws RemoteException;

    /** Stato del nodo, per la demo. */
    String status() throws RemoteException;
}
