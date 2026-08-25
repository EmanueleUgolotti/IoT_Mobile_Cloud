"""
Test rapido del modello .tflite convertito: carica un'immagine, esegue
l'inferenza, stampa le probabilita' per ciascuna classe.

Serve a verificare che la conversione .keras -> .tflite non abbia
introdotto problemi, PRIMA di integrare il modello in Android.

Esecuzione:
    python test_tflite_inference.py --model ../models/bpa_vgg16.tflite \
                                     --image /percorso/a/una/immagine.jpg
"""

import argparse

import numpy as np
from PIL import Image
import tensorflow as tf
from tensorflow.keras.applications.vgg16 import preprocess_input


# Ordine confermato dallo script di training: class_names = sorted(df["label"].unique())
# Ordinamento lessicografico su stringhe (non numerico), da cui:
# blunt < gun < paper100cm < paper25cm < paper50cm < paper75cm
CLASS_NAMES = [
    "blunt", "gun", "paper100cm", "paper25cm", "paper50cm", "paper75cm"
]


def load_and_preprocess_image(image_path: str, target_size=(224, 224)) -> np.ndarray:
    img = Image.open(image_path).convert("RGB")
    img = img.resize(target_size)
    array = np.array(img, dtype=np.float32)
    array = preprocess_input(array)  # stesso preprocessing usato in training (VGG16)
    array = np.expand_dims(array, axis=0)  # aggiunge la dimensione batch
    return array


def run_inference(model_path: str, image_path: str) -> None:
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print("Input details:", input_details[0]["shape"], input_details[0]["dtype"])
    print("Output details:", output_details[0]["shape"], output_details[0]["dtype"])

    input_data = load_and_preprocess_image(image_path)

    interpreter.set_tensor(input_details[0]["index"], input_data)
    interpreter.invoke()

    output = interpreter.get_tensor(output_details[0]["index"])[0]

    print("\nProbabilita' per classe:")
    for i, prob in enumerate(output):
        label = CLASS_NAMES[i] if i < len(CLASS_NAMES) else f"classe_{i}"
        print(f"  {label}: {prob:.4f}")

    predicted_idx = int(np.argmax(output))
    predicted_label = CLASS_NAMES[predicted_idx]
    print(f"\nClasse predetta: {predicted_label} (indice {predicted_idx}, "
          f"confidenza {output[predicted_idx]:.4f})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Testa il modello .tflite su un'immagine")
    parser.add_argument("--model", required=True, help="Percorso al file .tflite")
    parser.add_argument("--image", required=True, help="Percorso a un'immagine di test")
    args = parser.parse_args()

    run_inference(args.model, args.image)