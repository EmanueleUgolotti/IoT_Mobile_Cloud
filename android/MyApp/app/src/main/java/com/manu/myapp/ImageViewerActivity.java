package com.manu.myapp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Mostra una singola immagine a schermo intero, per guardarla meglio.
 * Riusabile da qualunque punto dell'app che abbia bisogno di
 * ingrandire un'immagine (Cronologia classificazioni, attacchi
 * adversarial, ecc.) - riceve il percorso del file salvato su
 * storage interno.
 */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_TITLE = "extra_title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        Toolbar toolbar = findViewById(R.id.toolbarImageViewer);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            getSupportActionBar().setTitle(title != null ? title : getString(R.string.viewer_default_title));
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        ImageView imageView = findViewById(R.id.imageViewerFull);
        String imagePath = getIntent().getStringExtra(EXTRA_IMAGE_PATH);

        if (imagePath != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
        }

        Button buttonShare = findViewById(R.id.buttonShareViewer);
        if (imagePath != null) {
            buttonShare.setOnClickListener(v -> ShareUtils.shareImage(this, imagePath));
        } else {
            buttonShare.setEnabled(false);
        }
    }
}