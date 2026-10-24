package bpa;

import java.util.*;

/** Uso: java bpa.Main <idNodo> <n_nodi_totali>   |   java bpa.Main client <idNodo> submit|status|get <arg> */
public class Main {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("client")) { client(a); return; }
        int id = Integer.parseInt(a[0]), n = Integer.parseInt(a[1]);
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= n; i++) ids.add(i);
        new Node(id, ids).start();
    }

    static void client(String[] a) throws Exception {
        var reg = java.rmi.registry.LocateRegistry.getRegistry("localhost", Node.BASE_PORT + Integer.parseInt(a[1]));
        NodeService s = (NodeService) reg.lookup("node");
        switch (a[2]) {
            case "status" -> System.out.println(s.status());
            case "submit" -> System.out.println(s.submit(a[3], a[3].getBytes()));
            case "get" -> System.out.println(s.get(a[3]));
            default -> System.out.println("comandi: status | submit <id> | get <id>");
        }
    }
}
