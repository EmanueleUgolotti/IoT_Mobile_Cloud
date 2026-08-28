"""
Attacchi black-box HSJ (HopSkipJump), ZOO, Boundary su VGG16.

Versione adattata per l'integrazione nel backend BPA: la logica di
attacco e' estratta in run_art_attack(), riutilizzabile dall'endpoint
backend. Mantenuta anche la modalita' da riga di comando per test
indipendenti.

Uso da linea di comando:
    python run_hsj_zoo_boundary_vgg16.py --image_path /percorso/immagine.jpg --attack Boundary
    python run_hsj_zoo_boundary_vgg16.py --image_path /percorso/immagine.jpg --attack HSJ

Uso come libreria (per l'endpoint backend):
    from run_hsj_zoo_boundary_vgg16 import run_art_attack
    result = run_art_attack(image_rgb_uint8, attack_name="Boundary")
"""

import os
import sys
import argparse

import cv2
import numpy as np
from PIL import Image

from art.estimators.classification import BlackBoxClassifier
from art.attacks.evasion import HopSkipJump, BoundaryAttack, ZooAttack

current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.append(current_dir)

import pipeline_predict
from pipeline_predict_vgg16 import DEFAULT_MODEL_PATH, IMG_SIZE


# Stesso "budget" di query usato per i modelli ML, per rendere il
# confronto vettori-vs-immagini il piu' equo possibile.
ATTACK_DEFAULTS = {
    "HSJ": {
        "max_iter": 50,
        "max_eval": 1000,
        "init_eval": 100,
        "verbose": True,
    },
    "Boundary": {
        "max_iter": 50,
        "delta": 0.01,
        "epsilon": 0.1,
        "verbose": True,
    },
    "ZOO": {
        "max_iter": 50,
        "binary_search_steps": 10,
        "initial_const": 1.0,
        "nb_parallel": 4,
        "variable_h": 1.0,
        "verbose": True,
    },
}


def print_prediction_block(label, class_names, probs):
    pred_idx = int(np.argmax(probs))
    prediction = class_names[pred_idx]
    print(f"  [{label}] -> {prediction}")
    for cls, prob in sorted(zip(class_names, probs), key=lambda x: x[1], reverse=True):
        print(f"      - {cls:<18}: {prob:.2%}")
    return prediction


def build_attack(attack_name, art_classifier, params):
    if attack_name == "HSJ":
        return HopSkipJump(classifier=art_classifier, **params)
    elif attack_name == "Boundary":
        return BoundaryAttack(estimator=art_classifier, targeted=False, **params)
    elif attack_name == "ZOO":
        return ZooAttack(classifier=art_classifier, **params)
    else:
        raise ValueError(f"Attacco non riconosciuto: {attack_name}")


def run_art_attack(img_rgb_uint8: np.ndarray, attack_name: str, model=None, class_names=None,
                    model_path: str = DEFAULT_MODEL_PATH, max_iter: int = None):
    """
    Esegue un attacco ART (HSJ, Boundary o ZOO) su una singola immagine.
    Pensata per essere richiamata dall'endpoint backend.

    img_rgb_uint8: array numpy (H, W, 3), RGB, valori 0-255, QUALSIASI
        risoluzione - viene ridimensionata a IMG_SIZE (224x224) subito
        sotto, prima di eseguire l'attacco. Motivo: il modello vede
        comunque solo 224x224 (pipeline_predict la ridimensiona
        internamente ad ogni predizione); far lavorare l'attacco
        sull'immagine a risoluzione originale sprecava memoria e tempo
        di calcolo senza alcun beneficio, ed era la causa di crash per
        esaurimento memoria su immagini ad alta risoluzione.

    Restituisce un dizionario con: immagine avversaria (uint8 RGB),
    predizione originale, predizione avversaria, esito (SUCC/FAIL),
    probabilita' per classe, metriche di perturbazione (L2, Linf).
    """
    if attack_name not in ATTACK_DEFAULTS:
        raise ValueError(f"Attacco non riconosciuto: {attack_name}")

    if model is None:
        model = pipeline_predict.load_model("vgg16", model_path)
    if class_names is None:
        class_names = pipeline_predict.get_class_names("vgg16", model)

    n_classes = len(class_names)

    # Ridimensionamento a 224x224 PRIMA dell'attacco (stesso algoritmo
    # usato internamente da pipeline_predict_vgg16, per coerenza)
    img_resized = cv2.resize(img_rgb_uint8, IMG_SIZE, interpolation=cv2.INTER_LANCZOS4)
    img_batch = img_resized.astype(np.float32)[np.newaxis, ...]

    def predict_fn(x_batch: np.ndarray) -> np.ndarray:
        return pipeline_predict.predict_proba_batch(x_batch, "vgg16", model)

    art_classifier = BlackBoxClassifier(
        predict_fn=predict_fn,
        input_shape=img_batch.shape[1:],
        nb_classes=n_classes,
        clip_values=(0.0, 255.0),
    )
    art_classifier.channels_first = False

    params = dict(ATTACK_DEFAULTS[attack_name])
    if max_iter is not None:
        params["max_iter"] = max_iter

    probs_orig = predict_fn(img_batch)[0]
    pred_orig_idx = int(np.argmax(probs_orig))
    pred_orig = class_names[pred_orig_idx]

    attack = build_attack(attack_name, art_classifier, params)
    x_adv = attack.generate(x=img_batch)

    probs_adv = predict_fn(x_adv)[0]
    pred_adv_idx = int(np.argmax(probs_adv))
    pred_adv = class_names[pred_adv_idx]

    l2_pixel = float(np.linalg.norm((x_adv - img_batch).reshape(-1)))
    linf_pixel = float(np.max(np.abs(x_adv - img_batch)))

    adv_img_uint8 = np.clip(x_adv[0], 0, 255).astype(np.uint8)

    return {
        "adversarial_image_rgb": adv_img_uint8,
        "prediction_original": pred_orig,
        "probabilities_original": dict(zip(class_names, probs_orig.tolist())),
        "prediction_adversarial": pred_adv,
        "probabilities_adversarial": dict(zip(class_names, probs_adv.tolist())),
        "success": pred_orig != pred_adv,
        "l2_pixel": l2_pixel,
        "linf_pixel": linf_pixel,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="HSJ / ZOO / Boundary Attack su VGG16 (backend BPA)")
    parser.add_argument("--image_path", type=str, required=True)
    parser.add_argument(
        "--attack", type=str, required=True, choices=["HSJ", "ZOO", "Boundary"],
        help="Attacco da eseguire"
    )
    parser.add_argument("--max_iter", type=int, default=None, help="Override del max_iter di default")
    parser.add_argument("--model_path", type=str, default=DEFAULT_MODEL_PATH)
    args = parser.parse_args()

    output_dir = os.path.join("adversarial_image", args.attack.lower() + "_vgg16")
    os.makedirs(output_dir, exist_ok=True)

    print(f"Caricamento modello da {args.model_path}...")
    model = pipeline_predict.load_model("vgg16", args.model_path)
    class_names = pipeline_predict.get_class_names("vgg16", model)
    print(f"Classi ({len(class_names)}): {class_names}")

    img = Image.open(args.image_path).convert("RGB")
    img_rgb_uint8 = np.array(img, dtype=np.uint8)

    result = run_art_attack(
        img_rgb_uint8, args.attack, model=model, class_names=class_names, max_iter=args.max_iter
    )

    probs_orig_arr = np.array([result["probabilities_original"][c] for c in class_names])
    probs_adv_arr = np.array([result["probabilities_adversarial"][c] for c in class_names])
    print_prediction_block("ORIGINALE", class_names, probs_orig_arr)
    print_prediction_block("AVVERSARIA", class_names, probs_adv_arr)
    print(f"  ESITO ATTACCO: {'SUCC' if result['success'] else 'FAIL'}")
    print(f"  L2 pixel (RGB raw): {result['l2_pixel']:.4f}  |  Linf pixel: {result['linf_pixel']:.4f}")

    base_name = os.path.splitext(os.path.basename(args.image_path))[0]
    adv_bgr = cv2.cvtColor(result["adversarial_image_rgb"], cv2.COLOR_RGB2BGR)
    out_path = os.path.join(output_dir, f"{base_name}_{args.attack.lower()}_adv.png")
    cv2.imwrite(out_path, adv_bgr)
    print(f"Salvato: {out_path}")