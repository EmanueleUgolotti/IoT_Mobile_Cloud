package bpa;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Classifica chiamando il servizio 'robustness' (VGG16) del backend, lo stesso usato da /cloud/ingest. */
public class Classifier {
    public static class Result { public final String label; public final double confidence;
        Result(String l, double c) { label = l; confidence = c; } }

    static final String URL = System.getProperty("robustness.url",
            System.getenv().getOrDefault("ROBUSTNESS_URL", "http://localhost:8001"));
    static final String API_KEY = System.getenv("API_KEY");   // opzionale, se AUTH_ENABLED=true
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    public static Result classify(byte[] data) {
        try {
            String b64 = Base64.getEncoder().encodeToString(data);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(URL + "/robustness/predict"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"images_data\":[\"" + b64 + "\"]}"));
            if (API_KEY != null) rb.header("X-API-Key", API_KEY);
            HttpResponse<String> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                String b = r.body();
                throw new RuntimeException("HTTP " + r.statusCode() + ": " + b.substring(0, Math.min(300, b.length())));
            }
            return parse(r.body());
        } catch (Exception e) {
            System.out.println("[Classifier] servizio robustness non disponibile: " + e.getMessage());
            return new Result("non-classificata", 0.0);
        }
    }

    /** Estrae class_names e probabilities[0] dal JSON (niente librerie esterne). */
    static Result parse(String json) {
        Matcher mn = Pattern.compile("\"class_names\"\\s*:\\s*\\[(.*?)\\]").matcher(json);
        Matcher mp = Pattern.compile("\"probabilities\"\\s*:\\s*\\[\\s*\\[(.*?)\\]").matcher(json);
        if (!mn.find() || !mp.find()) throw new RuntimeException("risposta inattesa");
        String[] names = mn.group(1).replace("\"", "").split("\\s*,\\s*");
        String[] probs = mp.group(1).split("\\s*,\\s*");
        int best = 0; double bp = -1;
        for (int i = 0; i < probs.length; i++) {
            double p = Double.parseDouble(probs[i].trim());
            if (p > bp) { bp = p; best = i; }
        }
        return new Result(names[best].trim(), bp);
    }
}