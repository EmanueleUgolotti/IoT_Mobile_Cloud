"""
Test rapido del modello .keras originale (prima della conversione TFLite):
carica un'immagine, esegue l'inferenza, stampa le probabilita' per ciascuna
classe.

Usa lo stesso preprocessing del training (preprocess_input di VGG16, NON
una normalizzazione /255 generica) e lo stesso ordine di classi confermato
dallo script di training (class_names = sorted(df["label"].unique())).

Esecuzione:
    python test_keras_inference.py --model /percorso/a/VGG16_best_model.keras \
                                    --image /percorso/a/immagine.jpg
"""

import argparse

import numpy as np
from PIL import Image
import tensorflow as tf
from tensorflow.keras.applications.vgg16 import preprocess_input


# Stesso ordine confermato per il modello .tflite: sorted() lessicografico
# su ["paper25cm","paper50cm","paper75cm","paper100cm","blunt","gun"]
CLASS_NAMES = [
    "blunt", "gun", "paper100cm", "paper25cm", "paper50cm", "paper75cm"
]

IMG_SIZE = (224, 224)


def load_and_preprocess_image(image_path: str) -> np.ndarray:
    img = Image.open(image_path).convert("RGB")
    img = img.resize(IMG_SIZE)
    array = np.array(img, dtype=np.float32)
    array = preprocess_input(array)  # stesso preprocessing usato in training
    array = np.expand_dims(array, axis=0)
    return array


def run_inference(model_path: str, image_path: str) -> None:
    print(f"Caricamento modello da: {model_path}")
    model = tf.keras.models.load_model(model_path)

    input_data = load_and_preprocess_image(image_path)
    output = model.predict(input_data, verbose=0)[0]

    print("\nProbabilita' per classe:")
    for i, prob in enumerate(output):
        label = CLASS_NAMES[i] if i < len(CLASS_NAMES) else f"classe_{i}"
        print(f"  {label}: {prob:.4f}")

    predicted_idx = int(np.argmax(output))
    predicted_label = CLASS_NAMES[predicted_idx]
    print(f"\nClasse predetta: {predicted_label} (indice {predicted_idx}, "
          f"confidenza {output[predicted_idx]:.4f})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Testa il modello .keras su un'immagine")
    parser.add_argument("--model", required=True, help="Percorso al file .keras")
    parser.add_argument("--image", required=True, help="Percorso a un'immagine di test")
    args = parser.parse_args()

    run_inference(args.model, args.image)