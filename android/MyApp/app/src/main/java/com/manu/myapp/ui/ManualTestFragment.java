package com.manu.myapp.ui;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.manu.myapp.AppState;
import com.manu.myapp.Classifier;
import com.manu.myapp.HistoryDbHelper;
import com.manu.myapp.ImageStorage;
import com.manu.myapp.ImageUtils;
import com.manu.myapp.ProfileManager;
import com.manu.myapp.R;
import com.manu.myapp.ResultDetailActivity;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Schermata Test manuale: permette di scegliere un'immagine dalla
 * galleria o scattarne una nuova con la fotocamera, classificarla
 * subito con lo stesso modello usato per le acquisizioni dal gateway,
 * e salvare il risultato in cronologia con fonte "Foto locale".
 *
 * Per le immagini scelte dalla galleria, il nome file mostrato e' quello
 * reale assegnato dal sistema operativo. Per le foto appena scattate
 * (non ancora salvate come file dal sistema) viene generato un nome nello
 * stesso formato usato da Android (IMG_<timestamp>.jpg).
 */
public class ManualTestFragment extends Fragment {

    private ImageView imagePreview;
    private LinearLayout resultCard;
    private TextView textResultFileName;
    private TextView textManualResult;
    private TextView textResultConfidence;
    private ProgressBar progressResultConfidence;
    private TextView textResultTapHint;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<Void> takePhotoLauncher;

    private Classifier.Result lastResult;
    private float[] lastProbabilities;
    private String lastImagePath;
    private String lastFileName;
    private long lastId;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        handlePickedImage(uri);
                    }
                });

        takePhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicturePreview(),
                bitmap -> {
                    if (bitmap != null) {
                        String fileName = "IMG_" + System.currentTimeMillis() + ".jpg";
                        handleCapturedBitmap(bitmap, fileName);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manual_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        imagePreview = view.findViewById(R.id.imagePreview);
        resultCard = view.findViewById(R.id.resultCard);
        textResultFileName = view.findViewById(R.id.textResultFileName);
        textManualResult = view.findViewById(R.id.textManualResult);
        textResultConfidence = view.findViewById(R.id.textResultConfidence);
        progressResultConfidence = view.findViewById(R.id.progressResultConfidence);
        textResultTapHint = view.findViewById(R.id.textResultTapHint);

        Button buttonPickImage = view.findViewById(R.id.buttonPickImage);
        Button buttonTakePhoto = view.findViewById(R.id.buttonTakePhoto);

        buttonPickImage.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
        buttonTakePhoto.setOnClickListener(v -> takePhotoLauncher.launch(null));
    }

    private void handlePickedImage(Uri uri) {
        if (getContext() == null) {
            return;
        }

        String fileName = queryDisplayName(uri);

        try {
            ContentResolver resolver = requireContext().getContentResolver();
            InputStream input = resolver.openInputStream(uri);
            Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(input);
            if (input != null) {
                input.close();
            }
            if (bitmap != null) {
                handleCapturedBitmap(bitmap, fileName);
            }
        } catch (Exception e) {
            textManualResult.setText(getString(R.string.manual_test_error));
        }
    }

    /** Legge il nome file reale assegnato dal sistema operativo, se disponibile. */
    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = requireContext().getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {
        }
        return "immagine_sconosciuta.jpg";
    }

    private void handleCapturedBitmap(Bitmap rawBitmap, String fileName) {
        final Bitmap bitmap = ImageUtils.capMaxDimension(rawBitmap);
        imagePreview.setImageBitmap(bitmap);
        textManualResult.setText(getString(R.string.manual_test_classifying));
        resultCard.setOnClickListener(null);

        Classifier classifier = AppState.getInstance().classifier;
        if (classifier == null) {
            textManualResult.setText(getString(R.string.manual_test_error));
            return;
        }

        executor.execute(() -> {
            Classifier.Result result = classifier.classify(bitmap);
            String imagePath = ImageStorage.save(requireContext(), bitmap, fileName);
            long timestamp = System.currentTimeMillis();

            String profileName = ProfileManager.getName(requireContext());
            String source = profileName.isEmpty()
                    ? "Foto locale"
                    : "Foto locale — " + profileName;

            HistoryDbHelper db = new HistoryDbHelper(requireContext());
            long id = db.insert(fileName, result.predictedLabel, result.confidence,
                    result.probabilities, source, timestamp, imagePath);

            lastResult = result;
            lastProbabilities = result.probabilities;
            lastImagePath = imagePath;
            lastFileName = fileName;
            lastId = id;

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> showResult(fileName, result, timestamp));
        });
    }

    private void showResult(String fileName, Classifier.Result result, long timestamp) {
        if (getView() == null) {
            return; // fragment non piu' visibile
        }

        textResultFileName.setText(fileName);
        textResultFileName.setVisibility(View.VISIBLE);

        textManualResult.setText(result.predictedLabel);

        int confidencePercent = Math.round(result.confidence * 100);
        textResultConfidence.setText(getString(R.string.home_confidence_format, confidencePercent));
        textResultConfidence.setVisibility(View.VISIBLE);

        progressResultConfidence.setProgress(confidencePercent);
        progressResultConfidence.setVisibility(View.VISIBLE);

        textResultTapHint.setVisibility(View.VISIBLE);

        resultCard.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), ResultDetailActivity.class);
            intent.putExtra(ResultDetailActivity.EXTRA_ID, lastId);
            intent.putExtra(ResultDetailActivity.EXTRA_FILE_NAME, lastFileName);
            intent.putExtra(ResultDetailActivity.EXTRA_LABEL, lastResult.predictedLabel);
            intent.putExtra(ResultDetailActivity.EXTRA_CONFIDENCE, lastResult.confidence);
            intent.putExtra(ResultDetailActivity.EXTRA_PROBABILITIES, lastProbabilities);
            intent.putExtra(ResultDetailActivity.EXTRA_IMAGE_PATH, lastImagePath);
            intent.putExtra(ResultDetailActivity.EXTRA_NOTE, "");
            startActivity(intent);
        });
    }
}