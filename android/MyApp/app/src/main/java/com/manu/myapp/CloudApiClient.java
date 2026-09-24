package com.manu.myapp;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * classificazioni gia' fatte lato server, i tentativi di scatto
 * falliti segnalati dal Gateway, e manda al Cloud le foto classificate
 * localmente sul telefono (direzione opposta, "sincronizza verso il
 * Cloud") - usato sia per la sincronizzazione nella Cronologia, sia
 * per la finestra live sul Cloud.
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

    /**
     * Manda al Cloud una foto classificata localmente sul telefono
     * (direzione opposta a fetchRecentResults) - riusa lo stesso
     * endpoint POST /cloud/ingest che il Gateway chiama normalmente:
     * dal punto di vista del backend non fa differenza chi manda la
     * richiesta, purche' il payload JSON sia nello stesso formato
     * atteso per la modalita' 'captured'.
     *
     * @param baseUrl     indirizzo del backend api
     * @param captureId   identificatore leggibile della foto (es. il
     *                    nome file gia' usato in Cronologia)
     * @param imageBytes  i byte grezzi del file JPEG da mandare
     * @return true se il backend ha accettato la richiesta (HTTP 2xx)
     */
    public static boolean uploadCapturedPhoto(String baseUrl, String captureId, byte[] imageBytes)
            throws IOException, JSONException {
        String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

        JSONObject payload = new JSONObject();
        // request_id univoco basato sul tempo - stesso principio gia'
        // usato lato firmware per evitare lo stesso problema di
        // duplicati gia' risolto altrove nel progetto (un secondo
        // invio con lo stesso id verrebbe rifiutato dal database).
        String requestId = "app-" + captureId + "-" + System.currentTimeMillis();
        payload.put("request_id", requestId);
        payload.put("node_id", 0); // 0 = non proveniente da un vero Acquisition Node, mandato dall'app
        // NON System.currentTimeMillis() diretto: e' un numero enorme
        // (~1.79 x 10^12 oggi), che rischia di superare il limite di un
        // campo INTEGER a 32 bit nel database (~2.1 x 10^9), causando un
        // errore "integer out of range" lato PostgreSQL. Il resto (%
        // 1000000) mantiene comunque un valore che cambia nel tempo,
        // restando pero' sempre ben dentro il limite - seq_num qui non
        // serve comunque per garantire l'unicita' (ci pensa gia'
        // request_id, costruito sopra con l'orario esatto), solo per
        // coerenza con lo schema dei pacchetti del Gateway.
        payload.put("seq_num", (int) (System.currentTimeMillis() % 1_000_000));
        payload.put("gateway_timestamp", "1970-01-01T00:00:00Z");
        payload.put("mode", "captured");
        payload.put("capture_id", captureId);
        payload.put("image_data", base64Image);
        payload.put("retry_attempt", 0);

        return performPostJson(baseUrl + "/cloud/ingest", payload.toString());
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

    private static boolean performPostJson(String urlString, String jsonBody) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            boolean success = responseCode >= 200 && responseCode < 300;

            if (!success) {
                // Legge il corpo della risposta di errore (spesso contiene
                // il motivo preciso del rifiuto, es. un campo mancante nel
                // JSON) - senza questo, sappiamo solo "ha fallito", non "perche'".
                String errorBody = readErrorStream(conn);
                Log.w("BPA_CloudApiClient", "POST " + urlString + " -> HTTP " + responseCode
                        + " - corpo risposta: " + errorBody);
            }

            return success;

        } finally {
            conn.disconnect();
        }
    }

    private static String readErrorStream(HttpURLConnection conn) {
        try (InputStream errorStream = conn.getErrorStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (errorStream == null) {
                return "(nessun corpo nella risposta di errore)";
            }
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = errorStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return "(errore nella lettura del corpo della risposta: " + e + ")";
        }
    }
}