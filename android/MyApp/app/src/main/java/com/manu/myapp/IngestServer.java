package com.manu.myapp;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Server HTTP locale sul telefono: riceve le acquisizioni dal Gateway
 * (ESP32 #2) quando l'offloading decide "mobile" invece di "cloud".
 * Espone POST /mobile/ingest, stesso schema JSON usato dal backend cloud.
 */
public class IngestServer extends NanoHTTPD {

    public interface OnIngestListener {
        void onIngestReceived(JSONObject payload);
    }

    private final OnIngestListener listener;

    public IngestServer(int port, OnIngestListener listener) {
        super(port);
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (!"/mobile/ingest".equals(session.getUri()) || session.getMethod() != Method.POST) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }

        try {
            String body = readRequestBody(session);
            JSONObject json = new JSONObject(body);

            String mode = json.optString("mode", null);
            if (mode == null || (!mode.equals("reference") && !mode.equals("captured"))) {
                return jsonError(Response.Status.BAD_REQUEST, "Unknown or missing mode: " + mode);
            }

            // Validazione minima dei campi obbligatori comuni
            String[] requiredCommon = {"request_id", "node_id", "seq_num", "gateway_timestamp"};
            for (String field : requiredCommon) {
                if (!json.has(field)) {
                    return jsonError(Response.Status.UNPROCESSABLE_ENTITY, "Missing field: " + field);
                }
            }

            if (mode.equals("reference")) {
                if (!json.has("image_ref") || !json.has("dataset_id")) {
                    return jsonError(Response.Status.UNPROCESSABLE_ENTITY, "Missing image_ref or dataset_id");
                }
            } else { // captured
                if (!json.has("capture_id") || !json.has("image_data")) {
                    return jsonError(Response.Status.UNPROCESSABLE_ENTITY, "Missing capture_id or image_data");
                }
            }

            if (listener != null) {
                listener.onIngestReceived(json);
            }

            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("request_id", json.getString("request_id"));
            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());

        } catch (Exception e) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Server error: " + e.getMessage());
        }
    }

    private String readRequestBody(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        // NanoHTTPD mette il body grezzo nella chiave "postData" per POST non-form
        String body = session.getQueryParameterString();
        // Fallback: leggiamo direttamente dagli header/parametri già parsati da parseBody
        return files.containsKey("postData") ? files.get("postData") : (body != null ? body : "");
    }

    private Response jsonError(Response.Status status, String detail) {
        JSONObject error = new JSONObject();
        try {
            error.put("detail", detail);
        } catch (Exception ignored) {}
        return newFixedLengthResponse(status, "application/json", error.toString());
    }
}