import json
import os
import cv2
import numpy as np
import tensorflow as tf
from tensorflow.keras.applications.vgg16 import preprocess_input

IMG_SIZE = (224, 224)

# Percorsi adattati alla struttura del nostro progetto: il modello e i
# metadati delle classi vivono entrambi nella cartella models/ condivisa
# del progetto (stessa cartella usata anche per il modello .tflite
# dell'app mobile), non replicati dentro backend/robustness/.
DEFAULT_MODEL_PATH = "models/best_model.keras"
DEFAULT_CLASS_INDICES_PATH = "models/class_indices.json"


def load_model(model_path: str = DEFAULT_MODEL_PATH):
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Modello non trovato: {model_path}")
    return tf.keras.models.load_model(model_path)


def get_class_names(class_indices_path: str = DEFAULT_CLASS_INDICES_PATH) -> list:
    with open(class_indices_path) as f:
        class_indices = json.load(f)
    return sorted(class_indices.keys(), key=lambda k: class_indices[k])


def predict_proba(img_rgb: np.ndarray, model) -> np.ndarray:
    return predict_proba_batch(img_rgb[np.newaxis, ...], model)[0]


# Interroga il modello su un BATCH di immagini RGB grezze (N, H, W, 3)
def predict_proba_batch(img_rgb_batch: np.ndarray, model) -> np.ndarray:
    resized_batch = []
    for img_rgb in img_rgb_batch:
        img_uint8 = np.clip(img_rgb, 0, 255).astype(np.uint8)
        img_resized = cv2.resize(img_uint8, IMG_SIZE, interpolation=cv2.INTER_LANCZOS4)
        resized_batch.append(img_resized.astype(np.float32))
    x_resized = np.stack(resized_batch, axis=0)
    x_preprocessed = preprocess_input(x_resized)  # sottrazione media + RGB->BGR
    probs = model.predict(x_preprocessed, verbose=0)
    return probs.astype(np.float32)