package com.manu.myapp;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Carica il modello bpa_vgg16.tflite ed esegue l'inferenza su un'immagine.
 *
 * Preprocessing: replica ESATTAMENTE quello usato in training
 * (tensorflow.keras.applications.vgg16.preprocess_input, modalita' 'caffe'):
 *   1. Ridimensionamento BICUBICO a 224x224 (stesso algoritmo di default
 *      usato da PIL/Pillow in Python, per allineare i risultati tra
 *      l'app mobile e gli script Python usati per gli esperimenti)
 *   2. Conversione RGB -> BGR
 *   3. Sottrazione della media ImageNet per canale (NON normalizzazione /255)
 *      B -= 103.939, G -= 116.779, R -= 123.68
 *
 * Ordine delle classi confermato da class_indices.json del training:
 *   0: blunt, 1: gun, 2: paper100cm, 3: paper25cm, 4: paper50cm, 5: paper75cm
 */
public class Classifier {

    public static final String[] CLASS_NAMES = {
            "blunt", "gun", "paper100cm", "paper25cm", "paper50cm", "paper75cm"
    };

    private static final int IMG_SIZE = 224;
    private static final int NUM_CHANNELS = 3;
    private static final int BYTES_PER_FLOAT = 4;

    // Medie ImageNet (ordine BGR), usate da preprocess_input di Keras/VGG16
    private static final float MEAN_B = 103.939f;
    private static final float MEAN_G = 116.779f;
    private static final float MEAN_R = 123.68f;

    // Parametro 'a' della convoluzione cubica usato da PIL/Pillow per BICUBIC
    private static final double CUBIC_A = -0.5;

    private final Interpreter interpreter;

    public Classifier(AssetManager assetManager, String modelFileName) throws IOException {
        interpreter = new Interpreter(loadModelFile(assetManager, modelFileName));
    }

    /**
     * Risultato della classificazione: etichetta + probabilita' per ciascuna classe.
     */
    public static class Result {
        public final String predictedLabel;
        public final float confidence;
        public final float[] probabilities; // stesso ordine di CLASS_NAMES

        Result(String predictedLabel, float confidence, float[] probabilities) {
            this.predictedLabel = predictedLabel;
            this.confidence = confidence;
            this.probabilities = probabilities;
        }
    }

    public Result classify(Bitmap bitmap) {
        ByteBuffer input = preprocess(bitmap);

        float[][] output = new float[1][CLASS_NAMES.length];
        interpreter.run(input, output);

        float[] probs = output[0];
        int bestIdx = 0;
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > probs[bestIdx]) {
                bestIdx = i;
            }
        }

        return new Result(CLASS_NAMES[bestIdx], probs[bestIdx], probs);
    }

    private ByteBuffer preprocess(Bitmap bitmap) {
        int[] resizedPixels = bicubicResize(bitmap, IMG_SIZE, IMG_SIZE);

        ByteBuffer buffer = ByteBuffer.allocateDirect(
                IMG_SIZE * IMG_SIZE * NUM_CHANNELS * BYTES_PER_FLOAT);
        buffer.order(ByteOrder.nativeOrder());

        for (int pixel : resizedPixels) {
            float r = (pixel >> 16) & 0xFF;
            float g = (pixel >> 8) & 0xFF;
            float b = pixel & 0xFF;

            // Ordine BGR + sottrazione media, come preprocess_input di VGG16
            buffer.putFloat(b - MEAN_B);
            buffer.putFloat(g - MEAN_G);
            buffer.putFloat(r - MEAN_R);
        }

        buffer.rewind();
        return buffer;
    }

    /**
     * Ridimensionamento bicubico, equivalente all'algoritmo di default usato
     * da PIL/Pillow (Image.resize senza parametro resample esplicito).
     * Android non offre un bicubico integrato (Bitmap.createScaledBitmap fa
     * solo bilineare), quindi lo implementiamo manualmente per allineare i
     * risultati tra app mobile e script Python.
     */
    private int[] bicubicResize(Bitmap src, int dstWidth, int dstHeight) {
        // Pre-ridimensionamento con l'algoritmo nativo (veloce, non
        // duplica l'intera immagine in un array Java) se la sorgente e'
        // molto piu' grande del necessario - evita OutOfMemoryError con
        // foto ad alta risoluzione, dato che il bicubico sotto lavora
        // copiando i pixel in un array int[] (costoso in memoria se
        // fatto sull'immagine a piena risoluzione).
        int maxIntermediateDim = Math.max(dstWidth, dstHeight) * 2;
        if (src.getWidth() > maxIntermediateDim || src.getHeight() > maxIntermediateDim) {
            double scale = (double) maxIntermediateDim /
                    Math.max(src.getWidth(), src.getHeight());
            int intermediateWidth = Math.max(1, (int) (src.getWidth() * scale));
            int intermediateHeight = Math.max(1, (int) (src.getHeight() * scale));
            Bitmap prescaled = Bitmap.createScaledBitmap(src, intermediateWidth, intermediateHeight, true);
            int[] result = bicubicResizeInternal(prescaled, dstWidth, dstHeight);
            prescaled.recycle();
            return result;
        }
        return bicubicResizeInternal(src, dstWidth, dstHeight);
    }

    private int[] bicubicResizeInternal(Bitmap src, int dstWidth, int dstHeight) {
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();

        int[] srcPixels = new int[srcWidth * srcHeight];
        src.getPixels(srcPixels, 0, srcWidth, 0, 0, srcWidth, srcHeight);

        int[] dstPixels = new int[dstWidth * dstHeight];

        double scaleX = (double) srcWidth / dstWidth;
        double scaleY = (double) srcHeight / dstHeight;

        for (int dy = 0; dy < dstHeight; dy++) {
            double srcY = (dy + 0.5) * scaleY - 0.5;
            int y0 = (int) Math.floor(srcY);
            double fy = srcY - y0;

            for (int dx = 0; dx < dstWidth; dx++) {
                double srcX = (dx + 0.5) * scaleX - 0.5;
                int x0 = (int) Math.floor(srcX);
                double fx = srcX - x0;

                double r = 0, g = 0, b = 0;

                for (int m = -1; m <= 2; m++) {
                    int yy = clamp(y0 + m, 0, srcHeight - 1);
                    double wy = cubicWeight(m - fy);

                    for (int n = -1; n <= 2; n++) {
                        int xx = clamp(x0 + n, 0, srcWidth - 1);
                        double wx = cubicWeight(n - fx);
                        double w = wx * wy;

                        int pixel = srcPixels[yy * srcWidth + xx];
                        r += w * ((pixel >> 16) & 0xFF);
                        g += w * ((pixel >> 8) & 0xFF);
                        b += w * (pixel & 0xFF);
                    }
                }

                int ri = clamp((int) Math.round(r), 0, 255);
                int gi = clamp((int) Math.round(g), 0, 255);
                int bi = clamp((int) Math.round(b), 0, 255);

                dstPixels[dy * dstWidth + dx] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
            }
        }

        return dstPixels;
    }

    private double cubicWeight(double t) {
        double x = Math.abs(t);
        if (x <= 1.0) {
            return (CUBIC_A + 2) * x * x * x - (CUBIC_A + 3) * x * x + 1;
        } else if (x < 2.0) {
            return CUBIC_A * x * x * x - 5 * CUBIC_A * x * x + 8 * CUBIC_A * x - 4 * CUBIC_A;
        }
        return 0.0;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String modelFileName) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(modelFileName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public void close() {
        interpreter.close();
    }
}