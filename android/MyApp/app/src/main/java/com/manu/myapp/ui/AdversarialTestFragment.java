package com.manu.myapp.ui;

import android.app.AlertDialog;
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

import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.HistoryItem;
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
 */
public class AdversarialTestFragment extends Fragment {

    private ImageView imagePreview;
    private RadioGroup radioGroupAttack;
    private EditText editMaxIter;
    private LinearLayout layoutProgress;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> pickImageLauncher;

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

        buttonPick.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        buttonPickFromHistory.setOnClickListener(v -> pickFromHistory());
        buttonRun.setOnClickListener(v -> runAttack());
    }

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

    private void runAttack() {
        if (selectedBitmap == null) {
            textError.setText(getString(R.string.adv_error_no_image));
            textError.setVisibility(View.VISIBLE);
            return;
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

        layoutProgress.setVisibility(View.VISIBLE);
        textError.setVisibility(View.GONE);
        layoutResult.setVisibility(View.GONE);

        String imageB64 = bitmapToBase64(selectedBitmap);
        Integer finalMaxIter = maxIter;

        executor.execute(() -> {
            try {
                JSONObject result = RobustnessApiClient.runAttack(
                        baseUrl, imageB64, attackName, finalMaxIter);

                Handler mainHandler = new Handler(Looper.getMainLooper());
                mainHandler.post(() -> showResult(result));

            } catch (Exception e) {
                Handler mainHandler = new Handler(Looper.getMainLooper());
                String message = e.getMessage();
                mainHandler.post(() -> showError(message));
            }
        });
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

    private void showResult(JSONObject result) {
        if (getView() == null) {
            return;
        }
        layoutProgress.setVisibility(View.GONE);

        try {
            boolean success = result.getBoolean("success");
            String predOriginal = result.getString("prediction_original");
            String predAdversarial = result.getString("prediction_adversarial");
            String advImageB64 = result.getString("adversarial_image_data");

            textOutcome.setText(success
                    ? getString(R.string.adv_outcome_success)
                    : getString(R.string.adv_outcome_fail));
            textOutcome.setTextColor(success ? 0xFFC62828 : 0xFF2E7D32);

            imageOriginal.setImageBitmap(selectedBitmap);
            textOriginalLabel.setText(predOriginal);

            byte[] advBytes = Base64.decode(advImageB64, Base64.DEFAULT);
            Bitmap advBitmap = BitmapFactory.decodeByteArray(advBytes, 0, advBytes.length);
            imagePerturbed.setImageBitmap(advBitmap);
            textAdversarialLabel.setText(predAdversarial);

            fillProbabilities(containerProbsOriginal, result.getJSONObject("probabilities_original"));
            fillProbabilities(containerProbsAdversarial, result.getJSONObject("probabilities_adversarial"));

            layoutResult.setVisibility(View.VISIBLE);

        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    private void fillProbabilities(LinearLayout container, JSONObject probs) throws Exception {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());

        Iterator<String> keys = probs.keys();
        while (keys.hasNext()) {
            String className = keys.next();
            double prob = probs.getDouble(className);

            View row = inflater.inflate(R.layout.item_probability_bar, container, false);
            TextView textClassName = row.findViewById(R.id.textClassName);
            TextView textClassPercent = row.findViewById(R.id.textClassPercent);
            ProgressBar progressClass = row.findViewById(R.id.progressClass);

            int percent = (int) Math.round(prob * 100);
            textClassName.setText(className);
            textClassPercent.setText(percent + "%");
            progressClass.setProgress(percent);

            container.addView(row);
        }
    }
}