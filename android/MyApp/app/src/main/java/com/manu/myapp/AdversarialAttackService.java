package com.manu.myapp;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Esegue un attacco adversarial in background, indipendentemente dal
 * fatto che l'utente resti sulla schermata, cambi scheda, o esca del
 * tutto dall'app. Mostra una notifica persistente mentre l'attacco e'
 * in corso (obbligatoria per un Foreground Service) e una notifica
 * finale con l'esito al termine.
 *
 * Il risultato viene salvato direttamente in AdversarialHistoryDbHelper
 * da qui - l'utente lo trova nella schermata "Cronologia attacchi"
 * quando vuole, senza dover restare in attesa.
 */
public class AdversarialAttackService extends Service {

    private static final String TAG = "BPA_AdversarialService";
    static final String CHANNEL_ID = "adversarial_attacks";
    private static final int NOTIFICATION_ID_PROGRESS = 1001;
    private static final int NOTIFICATION_ID_RESULT = 1002;

    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_ATTACK_NAME = "extra_attack_name";
    public static final String EXTRA_MAX_ITER = "extra_max_iter";
    public static final String EXTRA_BASE_URL = "extra_base_url";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
        String attackName = intent.getStringExtra(EXTRA_ATTACK_NAME);
        int maxIter = intent.getIntExtra(EXTRA_MAX_ITER, -1);
        String baseUrl = intent.getStringExtra(EXTRA_BASE_URL);

        startForeground(NOTIFICATION_ID_PROGRESS, buildProgressNotification(attackName));

        executor.execute(() ->
                runAttackAndNotify(imagePath, attackName, maxIter < 0 ? null : maxIter, baseUrl, startId));

        return START_NOT_STICKY;
    }

    private void runAttackAndNotify(String imagePath, String attackName, Integer maxIter,
                                    String baseUrl, int startId) {
        try {
            Bitmap originalBitmap = BitmapFactory.decodeFile(imagePath);
            if (originalBitmap == null) {
                throw new IllegalStateException("Impossibile leggere l'immagine salvata");
            }

            String imageB64 = bitmapToBase64(originalBitmap);
            JSONObject result = RobustnessApiClient.runAttack(baseUrl, imageB64, attackName, maxIter);

            boolean success = result.getBoolean("success");
            saveResultToHistory(attackName, result, originalBitmap);

            showResultNotification(attackName, success, null);

        } catch (Exception e) {
            Log.e(TAG, "Errore durante l'attacco", e);
            showResultNotification(attackName, false, e.getMessage());
        } finally {
            if (imagePath != null) {
                new File(imagePath).delete();
            }
            stopForeground(true);
            stopSelf(startId);
        }
    }

    private void saveResultToHistory(String attackName, JSONObject result, Bitmap originalBitmap) throws Exception {
        String predOriginal = result.getString("prediction_original");
        String predAdversarial = result.getString("prediction_adversarial");
        boolean success = result.getBoolean("success");
        String advImageB64 = result.getString("adversarial_image_data");

        byte[] advBytes = Base64.decode(advImageB64, Base64.DEFAULT);
        Bitmap advBitmap = BitmapFactory.decodeByteArray(advBytes, 0, advBytes.length);

        String originalPath = ImageStorage.save(this, originalBitmap, "adv_original");
        String adversarialPath = ImageStorage.save(this, advBitmap, "adv_result");

        float[] probsOriginal = jsonToOrderedArray(result.getJSONObject("probabilities_original"));
        float[] probsAdversarial = jsonToOrderedArray(result.getJSONObject("probabilities_adversarial"));

        AdversarialHistoryDbHelper db = new AdversarialHistoryDbHelper(this);
        db.insert(attackName,
                predOriginal, probsOriginal, originalPath,
                predAdversarial, probsAdversarial, adversarialPath,
                success, System.currentTimeMillis());
    }

    private float[] jsonToOrderedArray(JSONObject probs) throws Exception {
        float[] result = new float[Classifier.CLASS_NAMES.length];
        for (int i = 0; i < Classifier.CLASS_NAMES.length; i++) {
            String className = Classifier.CLASS_NAMES[i];
            result[i] = probs.has(className) ? (float) probs.getDouble(className) : 0f;
        }
        return result;
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }

    private Notification buildProgressNotification(String attackName) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.adv_notification_progress_title))
                .setContentText(getString(R.string.adv_notification_progress_text, attackName))
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void showResultNotification(String attackName, boolean success, String errorMessage) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title;
        String text;
        if (errorMessage != null) {
            title = getString(R.string.adv_notification_error_title);
            text = errorMessage;
        } else {
            title = success
                    ? getString(R.string.adv_notification_success_title)
                    : getString(R.string.adv_notification_fail_title);
            text = getString(R.string.adv_notification_result_text, attackName);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID_RESULT, notification);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // servizio "started", non "bound" - non serve un binder
    }
}