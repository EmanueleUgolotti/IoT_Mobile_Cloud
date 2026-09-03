package com.manu.myapp.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.manu.myapp.AdversarialAttackService;
import com.manu.myapp.AdversarialHistoryDbHelper;
import com.manu.myapp.AdversarialHistoryItem;
import com.manu.myapp.Classifier;
import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.HistoryItem;
import com.manu.myapp.ImageStorage;
import com.manu.myapp.ImageUtils;
import com.manu.myapp.ImageViewerActivity;
import com.manu.myapp.R;
import com.manu.myapp.RobustnessApiClient;
import com.manu.myapp.SettingsManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Schermata "telecomando" per gli attacchi adversarial: l'app NON esegue
 * l'attacco - sceglie un'immagine, la manda al backend (che gira sul
 * PC/server, non sul telefono) via RobustnessApiClient, e mostra il
 * confronto tra la predizione originale e quella dopo l'attacco.
 *
 * Ogni attacco riuscito o meno viene salvato in una cronologia dedicata
 * (AdversarialHistoryDbHelper), richiamabile dal pulsante "Cronologia
 * attacchi". Le due immagini del risultato (originale/avversaria) sono
 * toccabili per essere viste ingrandite (ImageViewerActivity).
 */
public class AdversarialTestFragment extends Fragment {

    private ImageView imagePreview;
    private RadioGroup radioGroupAttack;
    private EditText editMaxIter;
    private LinearLayout layoutProgress;
    private TextView textAttackStarted;
    private TextView textError;
    private LinearLayout layoutResult;
    private TextView textOutcome;
    private ImageView imageOriginal;
    private ImageView imagePerturbed;
    private TextView textOriginalLabel;
    private TextView textAdversarialLabel;
    private LinearLayout containerProbsOriginal;
    private LinearLayout containerProbsAdversarial;

    private Bitmap selectedBitmap;
    // Percorsi delle immagini dell'ultimo risultato mostrato, salvate su
    // storage interno - servono per aprire ImageViewerActivity al tocco
    private String lastOriginalImagePath;
    private String lastAdversarialImagePath;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        loadSelectedImage(uri);
                    }
                });

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        runAttack(); // riprova ora che il permesso e' stato concesso
                    } else {
                        textError.setText(getString(R.string.adv_error_notification_permission));
                        textError.setVisibility(View.VISIBLE);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_adversarial_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        imagePreview = view.findViewById(R.id.imageAdvPreview);
        radioGroupAttack = view.findViewById(R.id.radioGroupAttack);
        editMaxIter = view.findViewById(R.id.editMaxIter);
        layoutProgress = view.findViewById(R.id.layoutAdvProgress);
        textAttackStarted = view.findViewById(R.id.textAdvAttackStarted);
        textError = view.findViewById(R.id.textAdvError);
        layoutResult = view.findViewById(R.id.layoutAdvResult);
        textOutcome = view.findViewById(R.id.textAdvOutcome);
        imageOriginal = view.findViewById(R.id.imageAdvOriginal);
        imagePerturbed = view.findViewById(R.id.imageAdvPerturbed);
        textOriginalLabel = view.findViewById(R.id.textAdvOriginalLabel);
        textAdversarialLabel = view.findViewById(R.id.textAdvAdversarialLabel);
        containerProbsOriginal = view.findViewById(R.id.containerProbsOriginal);
        containerProbsAdversarial = view.findViewById(R.id.containerProbsAdversarial);

        Button buttonPick = view.findViewById(R.id.buttonAdvPickImage);
        Button buttonPickFromHistory = view.findViewById(R.id.buttonAdvPickFromHistory);
        Button buttonRun = view.findViewById(R.id.buttonRunAttack);
        Button buttonViewHistory = view.findViewById(R.id.buttonAdvHistory);

        buttonPick.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        buttonPickFromHistory.setOnClickListener(v -> pickFromHistory());
        buttonRun.setOnClickListener(v -> runAttack());
        buttonViewHistory.setOnClickListener(v -> viewAttackHistory());

        imageOriginal.setOnClickListener(v -> openImageViewer(
                lastOriginalImagePath, getString(R.string.adv_original_label)));
        imagePerturbed.setOnClickListener(v -> openImageViewer(
                lastAdversarialImagePath, getString(R.string.adv_adversarial_label)));
    }

    private void openImageViewer(String imagePath, String title) {
        if (imagePath == null || getContext() == null) {
            return;
        }
        Intent intent = new Intent(getContext(), ImageViewerActivity.class);
        intent.putExtra(ImageViewerActivity.EXTRA_IMAGE_PATH, imagePath);
        intent.putExtra(ImageViewerActivity.EXTRA_TITLE, title);
        startActivity(intent);
    }

    // --- Scelta immagine di partenza (galleria o cronologia classificazioni) ---

    private void pickFromHistory() {
        if (getContext() == null) {
            return;
        }

        executor.execute(() -> {
            HistoryDbHelper db = new HistoryDbHelper(requireContext());
            List<HistoryItem> items = db.getAll();

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> showHistoryPicker(items));
        });
    }

    private void showHistoryPicker(List<HistoryItem> items) {
        if (getContext() == null) {
            return;
        }

        if (items.isEmpty()) {
            textError.setText(getString(R.string.adv_error_history_empty));
            textError.setVisibility(View.VISIBLE);
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            HistoryItem item = items.get(i);
            String time = dateFormat.format(new Date(item.timestampMillis));
            labels[i] = item.fileName + " — " + item.label + " (" + time + ")";
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.adv_pick_from_history)
                .setItems(labels, (dialog, which) -> loadImageFromHistory(items.get(which)))
                .show();
    }

    private void loadImageFromHistory(HistoryItem item) {
        if (item.imagePath == null) {
            textError.setText(getString(R.string.adv_error_loading_image));
            textError.setVisibility(View.VISIBLE);
            return;
        }

        Bitmap bitmap = BitmapFactory.decodeFile(item.imagePath);
        if (bitmap == null) {
            textError.setText(getString(R.string.adv_error_loading_image));
            textError.setVisibility(View.VISIBLE);
            return;
        }
        bitmap = ImageUtils.capMaxDimension(bitmap);

        selectedBitmap = bitmap;
        imagePreview.setImageBitmap(bitmap);
        layoutResult.setVisibility(View.GONE);
        textError.setVisibility(View.GONE);
    }

    private void loadSelectedImage(Uri uri) {
        if (getContext() == null) {
            return;
        }
        try {
            InputStream input = requireContext().getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (input != null) {
                input.close();
            }
            if (bitmap != null) {
                // Fondamentale: senza questo limite, un'immagine ad alta
                // risoluzione dalla galleria puo' causare esaurimento di
                // memoria/blocco del PC lato backend durante un attacco
                // (stesso problema gia' risolto per il Test Manuale).
                bitmap = ImageUtils.capMaxDimension(bitmap);
                selectedBitmap = bitmap;
                imagePreview.setImageBitmap(bitmap);
                layoutResult.setVisibility(View.GONE);
                textError.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            textError.setText(getString(R.string.adv_error_loading_image));
            textError.setVisibility(View.VISIBLE);
        }
    }

    // --- Esecuzione dell'attacco ---

    private void runAttack() {
        if (selectedBitmap == null) {
            textError.setText(getString(R.string.adv_error_no_image));
            textError.setVisibility(View.VISIBLE);
            return;
        }

        if (!hasNotificationPermission()) {
            requestNotificationPermission();
            return; // l'utente riprovera' dopo aver concesso il permesso
        }

        String attackName = radioGroupAttack.getCheckedRadioButtonId() == R.id.radioAttackSimba
                ? "simba" : "boundary";

        Integer maxIter = null;
        String maxIterText = editMaxIter.getText().toString().trim();
        if (!maxIterText.isEmpty()) {
            try {
                maxIter = Integer.parseInt(maxIterText);
            } catch (NumberFormatException ignored) {
                // resta null, il backend usera' il suo default
            }
        }

        String baseUrl = SettingsManager.getRobustnessUrl(requireContext());

        // Salva l'immagine su un file temporaneo: il servizio la legge da
        // li', invece di passarla come extra dell'Intent (che ha un limite
        // di dimensione stretto per immagini anche solo moderate)
        String tempImagePath = ImageStorage.save(requireContext(), selectedBitmap, "adv_pending");

        Intent serviceIntent = new Intent(requireContext(), AdversarialAttackService.class);
        serviceIntent.putExtra(AdversarialAttackService.EXTRA_IMAGE_PATH, tempImagePath);
        serviceIntent.putExtra(AdversarialAttackService.EXTRA_ATTACK_NAME, attackName);
        if (maxIter != null) {
            serviceIntent.putExtra(AdversarialAttackService.EXTRA_MAX_ITER, maxIter);
        }
        serviceIntent.putExtra(AdversarialAttackService.EXTRA_BASE_URL, baseUrl);

        requireContext().startForegroundService(serviceIntent);

        textError.setVisibility(View.GONE);
        layoutResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.GONE);
        textAttackStarted.setText(getString(R.string.adv_attack_started_message));
        textAttackStarted.setVisibility(View.VISIBLE);
    }

    private boolean hasNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return true; // permesso non richiesto prima di Android 13
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }

    private void showError(String message) {
        if (getView() == null) {
            return;
        }
        layoutProgress.setVisibility(View.GONE);
        textError.setText(getString(R.string.adv_error_generic, message));
        textError.setVisibility(View.VISIBLE);
    }

    // --- Cronologia degli attacchi ---

    private void viewAttackHistory() {
        if (getContext() == null) {
            return;
        }

        executor.execute(() -> {
            AdversarialHistoryDbHelper db = new AdversarialHistoryDbHelper(requireContext());
            List<AdversarialHistoryItem> items = db.getAll();

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> showAttackHistoryPicker(items));
        });
    }

    private void showAttackHistoryPicker(List<AdversarialHistoryItem> items) {
        if (getContext() == null) {
            return;
        }

        if (items.isEmpty()) {
            textError.setText(getString(R.string.adv_history_empty));
            textError.setVisibility(View.VISIBLE);
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            AdversarialHistoryItem item = items.get(i);
            String outcome = item.success
                    ? getString(R.string.adv_outcome_success_short)
                    : getString(R.string.adv_outcome_fail_short);
            String time = dateFormat.format(new Date(item.timestampMillis));
            labels[i] = item.attackName + " — " + item.predictionOriginal + " → "
                    + item.predictionAdversarial + " (" + outcome + ", " + time + ")";
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.adv_view_history)
                .setItems(labels, (dialog, which) -> displayStoredAttack(items.get(which)))
                .show();
    }

    /** Rimostra un attacco passato, riusando la stessa area di risultato. */
    private void displayStoredAttack(AdversarialHistoryItem item) {
        if (item.imagePathOriginal == null || item.imagePathAdversarial == null) {
            return;
        }

        Bitmap original = BitmapFactory.decodeFile(item.imagePathOriginal);
        Bitmap adversarial = BitmapFactory.decodeFile(item.imagePathAdversarial);
        if (original == null || adversarial == null) {
            textError.setText(getString(R.string.adv_error_loading_image));
            textError.setVisibility(View.VISIBLE);
            return;
        }

        selectedBitmap = original;
        imagePreview.setImageBitmap(original);

        lastOriginalImagePath = item.imagePathOriginal;
        lastAdversarialImagePath = item.imagePathAdversarial;

        textOutcome.setText(item.success
                ? getString(R.string.adv_outcome_success)
                : getString(R.string.adv_outcome_fail));
        textOutcome.setTextColor(item.success ? 0xFFC62828 : 0xFF2E7D32);

        imageOriginal.setImageBitmap(original);
        textOriginalLabel.setText(item.predictionOriginal);

        imagePerturbed.setImageBitmap(adversarial);
        textAdversarialLabel.setText(item.predictionAdversarial);

        fillProbabilitiesFromArray(containerProbsOriginal, item.probabilitiesOriginal);
        fillProbabilitiesFromArray(containerProbsAdversarial, item.probabilitiesAdversarial);

        textError.setVisibility(View.GONE);
        layoutResult.setVisibility(View.VISIBLE);
    }

    private void fillProbabilitiesFromArray(LinearLayout container, float[] probs) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());

        for (int i = 0; i < probs.length && i < Classifier.CLASS_NAMES.length; i++) {
            View row = inflater.inflate(R.layout.item_probability_bar, container, false);
            TextView textClassName = row.findViewById(R.id.textClassName);
            TextView textClassPercent = row.findViewById(R.id.textClassPercent);
            ProgressBar progressClass = row.findViewById(R.id.progressClass);

            int percent = Math.round(probs[i] * 100);
            textClassName.setText(Classifier.CLASS_NAMES[i]);
            textClassPercent.setText(percent + "%");
            progressClass.setProgress(percent);

            container.addView(row);
        }
    }
}