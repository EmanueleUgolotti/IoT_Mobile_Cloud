package com.manu.myapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Scarica un'immagine dal dataset condiviso sul backend Cloud
 * (backend/api/, endpoint /dataset/image), usato quando l'app riceve
 * un'acquisizione in modalita' 'reference' dal Gateway: il messaggio
 * contiene solo il nome del file, non i byte dell'immagine - vanno
 * scaricati da qui prima di poter classificare.
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

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("Download fallito (HTTP " + responseCode + ") per "
                        + datasetId + "/" + imageRef);
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