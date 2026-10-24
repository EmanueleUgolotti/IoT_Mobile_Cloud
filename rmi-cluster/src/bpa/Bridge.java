package bpa;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.rmi.registry.*;

/**
 * Ponte HTTP -> RMI: il backend Python non parla RMI, quindi parla con questo processo.
 *   POST /submit?id=<imageId>   (body = byte dell'immagine)  -> inoltra a un nodo vivo
 *   GET  /status                                              -> stato di ogni nodo
 *   GET  /get?id=<imageId>                                    -> record dal primo nodo che lo ha
 * Uso: java -cp out bpa.Bridge <n_nodi> [porta_http=9000]
 */
public class Bridge {
    static int N;

    static NodeService node(int id) {
        try { return (NodeService) LocateRegistry.getRegistry("localhost", Node.BASE_PORT + id).lookup("node"); }
        catch (Exception e) { return null; }
    }

    static void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    static String param(HttpExchange x, String k) {
        String q = x.getRequestURI().getQuery();
        if (q != null) for (String kv : q.split("&")) if (kv.startsWith(k + "=")) return kv.substring(k.length() + 1);
        return null;
    }

    public static void main(String[] a) throws Exception {
        N = Integer.parseInt(a[0]);
        int port = a.length > 1 ? Integer.parseInt(a[1]) : 9000;
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);

        s.createContext("/submit", x -> {
            try {
                String id = param(x, "id");
                if (id == null) { reply(x, 400, "manca id"); return; }
                byte[] data = x.getRequestBody().readAllBytes();
                for (int i = 1; i <= N; i++) {           // primo nodo raggiungibile
                    NodeService n = node(i);
                    if (n == null) continue;
                    try { reply(x, 200, n.submit(id, data)); return; } catch (Exception ignored) {}
                }
                reply(x, 503, "nessun nodo disponibile");
            } catch (Exception e) { reply(x, 500, e.toString()); }
        });

        s.createContext("/status", x -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= N; i++) {
                NodeService n = node(i);
                try { sb.append(n.status()); } catch (Exception e) { sb.append("node" + i + " GIU'"); }
                sb.append('\n');
            }
            reply(x, 200, sb.toString());
        });

        s.createContext("/get", x -> {
            try {
                String id = param(x, "id");
                for (int i = 1; i <= N; i++) {
                    NodeService n = node(i);
                    try { ImageRecord r = n.get(id); if (r != null) { reply(x, 200, r.toString()); return; } }
                    catch (Exception ignored) {}
                }
                reply(x, 404, "non trovata");
            } catch (Exception e) { reply(x, 500, e.toString()); }
        });

        s.start();
        System.out.println("Bridge HTTP->RMI su porta " + port + " (" + N + " nodi)");
    }
}
