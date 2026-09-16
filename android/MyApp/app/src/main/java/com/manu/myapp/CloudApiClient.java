package com.manu.myapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Client HTTP per il backend Cloud ("api"): scarica immagini dal
 * dataset condiviso (usato per la modalita' 'reference'), legge le
 * classificazioni gia' fatte lato server, e i tentativi di scatto
 * falliti segnalati dal Gateway - usato sia per la sincronizzazione
 * nella Cronologia, sia per la finestra "live" sul Cloud.
 */
public class CloudApiClient {

    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;
    private static final int READ_TIMEOUT_MS = 30 * 1000;

    /**
     * @param baseUrl   indirizzo del backend api (es. "http://192.168.1.100:8000")
     * @param datasetId es. "bpa_v1"
     * @param imageRef  nome del file, es. "Hf73.jpg"
     * @return i byte grezzi dell'immagine
     * @throws IOException se il download fallisce (rete, file non trovato, ecc.)
     */
    public static byte[] downloadDatasetImage(String baseUrl, String datasetId, String imageRef)
            throws IOException {
        String encodedDatasetId = URLEncoder.encode(datasetId, StandardCharsets.UTF_8.name());
        String encodedImageRef = URLEncoder.encode(imageRef, StandardCharsets.UTF_8.name());
        String urlString = baseUrl + "/dataset/image?dataset_id=" + encodedDatasetId
                + "&image_ref=" + encodedImageRef;

        return performGetRaw(urlString);
    }

    /**
     * Recupera le ultime classificazioni gia' fatte dal backend Cloud
     * (endpoint GET /cloud/results).
     *
     * @param baseUrl indirizzo del backend api
     * @param limit   numero massimo di risultati da recuperare
     * @return l'array JSON grezzo dei risultati
     */
    public static JSONArray fetchRecentResults(String baseUrl, int limit)
            throws IOException, JSONException {
        String urlString = baseUrl + "/cloud/results?limit=" + limit;
        byte[] response = performGetRaw(urlString);
        String body = new String(response, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(body);
        return root.getJSONArray("results");
    }

    /**
     * Recupera gli ultimi tentativi di scatto falliti, segnalati dal
     * Gateway (endpoint GET /cloud/failures) - usato dalla finestra
     * "live" sul Cloud, per mostrare anche gli scatti da rifare, non
     * solo quelli riusciti.
     *
     * @param baseUrl indirizzo del backend api
     * @param limit   numero massimo di fallimenti da recuperare
     * @return l'array JSON grezzo dei fallimenti
     */
    public static JSONArray fetchRecentFailures(String baseUrl, int limit)
            throws IOException, JSONException {
        String urlString = baseUrl + "/cloud/failures?limit=" + limit;
        byte[] response = performGetRaw(urlString);
        String body = new String(response, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(body);
        return root.getJSONArray("failures");
    }

    private static byte[] performGetRaw(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("Richiesta fallita (HTTP " + responseCode + ") per " + urlString);
            }

            try (InputStream input = conn.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                return output.toByteArray();
            }

        } finally {
            conn.disconnect();
        }
    }
}