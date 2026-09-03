package com.manu.myapp;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client HTTP verso il servizio backend degli attacchi adversarial
 * (backend/robustness/, che gira sul PC/server, non sul telefono).
 * Usa HttpURLConnection nativo di Android - nessuna libreria HTTP
 * esterna aggiunta, coerente con l'approccio tenuto finora nel progetto.
 */
public class RobustnessApiClient {

    // Gli attacchi (specialmente SimBA) possono richiedere diversi minuti
    private static final int READ_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;

    /**
     * Esegue un attacco adversarial chiamando il backend.
     *
     * @param baseUrl    indirizzo del servizio backend (es. "http://192.168.1.100:8001")
     * @param imageB64   immagine da attaccare, codificata in base64
     * @param attackName "boundary" oppure "simba"
     * @param maxIter    numero di iterazioni (null per usare il default del backend)
     * @return la risposta JSON del backend
     * @throws IOException se la chiamata fallisce (rete, timeout, errore del server)
     */
    public static JSONObject runAttack(String baseUrl, String imageB64, String attackName,
                                       Integer maxIter) throws IOException, JSONException {
        URL url = new URL(baseUrl + "/robustness/attack");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            JSONObject requestBody = new JSONObject();
            requestBody.put("image_data", imageB64);
            requestBody.put("attack", attackName);
            if (maxIter != null) {
                requestBody.put("max_iter", maxIter);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseBody = readStream(responseStream);

            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Errore dal server (HTTP " + responseCode + "): " + responseBody);
            }

            return new JSONObject(responseBody);

        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}