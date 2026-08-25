"""
Conversione del modello VGG16 (addestrato durante il tirocinio) dal formato
Keras (.keras) al formato TensorFlow Lite (.tflite), per l'uso nell'app
Android tramite inferenza locale.

Esecuzione (una tantum, non fa parte della pipeline runtime del progetto):
    python convert_to_tflite.py --input /percorso/Vgg16_best_models.keras \
                                 --output ../models/bpa_vgg16.tflite

Requisiti: tensorflow (stesso ambiente usato per addestrare il modello,
per evitare incompatibilita' di versione nel caricamento).
"""

import argparse
import tensorflow as tf


def convert(input_path: str, output_path: str, quantize: bool) -> None:
    print(f"Caricamento modello da: {input_path}")
    model = tf.keras.models.load_model(input_path)

    print("Input shape attesa dal modello:", model.input_shape)

    converter = tf.lite.TFLiteConverter.from_keras_model(model)

    if quantize:
        # Quantizzazione dinamica: riduce le dimensioni del file mantenendo
        # buona accuratezza, senza bisogno di un dataset di calibrazione.
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        print("Quantizzazione dinamica attivata.")

    print("Conversione in corso...")
    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) / 1024
    print(f"Modello convertito e salvato in: {output_path} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Converte un modello Keras in TFLite")
    parser.add_argument("--input", required=True, help="Percorso al file .keras")
    parser.add_argument("--output", required=True, help="Percorso di output per il file .tflite")
    parser.add_argument(
        "--no-quantize",
        action="store_true",
        help="Disattiva la quantizzazione (file piu' grande, conversione 1:1)",
    )
    args = parser.parse_args()

    convert(args.input, args.output, quantize=not args.no_quantize)