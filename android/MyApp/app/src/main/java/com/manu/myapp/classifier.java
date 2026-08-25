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
 *   1. Conversione RGB -> BGR
 *   2. Sottrazione della media ImageNet per canale (NON normalizzazione /255)
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
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true);

        ByteBuffer buffer = ByteBuffer.allocateDirect(
                IMG_SIZE * IMG_SIZE * NUM_CHANNELS * BYTES_PER_FLOAT);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[IMG_SIZE * IMG_SIZE];
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE);

        for (int pixel : pixels) {
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