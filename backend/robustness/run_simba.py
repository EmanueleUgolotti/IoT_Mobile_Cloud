"""
Attacco SimBA su VGG16 (black-box).

Versione adattata per l'integrazione nel backend BPA: la modalita' ML
classico (--model_type ml) e' stata rimossa da questo file, dato che
per ora il backend integra solo VGG16 (vedi pipeline_predict.py). Lo
script originale del tirocinio supporta anche i modelli ML classici;
questa versione e' un sottoinsieme, non una sostituzione.

Uso da linea di comando:
    python run_simba.py --image_path /percorso/immagine.jpg
    python run_simba.py --data_root /percorso/cartella --num_runs 10

Uso come libreria (per l'endpoint backend):
    from run_simba import run_simba_attack
    result = run_simba_attack(image_rgb_uint8, true_label_index=0)
"""

import os
import sys
import argparse

import torch
import torch.nn as nn
import torchvision.transforms as transforms
import numpy as np
import cv2
from PIL import Image

current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.append(current_dir)

import pipeline_predict
from pipeline_predict_vgg16 import DEFAULT_MODEL_PATH
from simba.simba import SimBA


IMG_SIZE = 512


class PipelinePredictWrapper(nn.Module):

    def __init__(self, model_type: str, model):
        super().__init__()
        self.model_type = model_type
        self.model = model

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: tensore (N, C, H, W) in [0, 1] -> array (N, H, W, C) in [0, 255]
        img_np = x.detach().cpu().numpy().transpose(0, 2, 3, 1)
        img_rgb_255 = (img_np * 255.0).clip(0, 255)

        probs = pipeline_predict.predict_proba_batch(img_rgb_255, self.model_type, self.model)
        return torch.tensor(probs).float()


def get_prediction_details(model_type, model, class_names, img_tensor):
    img_np = img_tensor.detach().cpu().numpy().transpose(0, 2, 3, 1)
    img_rgb_255 = (img_np * 255.0).clip(0, 255)
    probs = pipeline_predict.predict_proba_batch(img_rgb_255, model_type, model)[0]
    pred_idx = int(np.argmax(probs))
    prediction = class_names[pred_idx]
    prob_dict = dict(zip(class_names, probs))
    return prediction, prob_dict


def print_prediction_block(label, prediction, prob_dict):
    print(f"  [{label}] -> {prediction}")
    for cls, prob in sorted(prob_dict.items(), key=lambda x: x[1], reverse=True):
        print(f"      - {cls:<18}: {prob:.2%}")


def run_simba_attack(img_rgb_uint8: np.ndarray, model=None, class_names=None,
                      model_path: str = DEFAULT_MODEL_PATH,
                      max_iters: int = 500, epsilon: float = 0.2,
                      freq_dims: int = 20, pixel_attack: bool = False):
    """
    Esegue l'attacco SimBA su una singola immagine (funzione pensata per
    essere richiamata dall'endpoint backend, non da linea di comando).

    img_rgb_uint8: array numpy (H, W, 3), RGB, valori 0-255.
    model / class_names: se gia' caricati altrove, evita di ricaricare
        il modello ad ogni chiamata (costoso). Se None, li carica qui.

    Restituisce un dizionario con: immagine avversaria (uint8 RGB),
    predizione originale, predizione avversaria, esito (SUCC/FAIL),
    probabilita' per classe originali e avversarie.
    """
    if model is None:
        model = pipeline_predict.load_model("vgg16", model_path)
    if class_names is None:
        class_names = pipeline_predict.get_class_names("vgg16", model)

    transform = transforms.Compose([
        transforms.ToPILImage(),
        transforms.Resize((IMG_SIZE, IMG_SIZE)),
        transforms.ToTensor(),
    ])
    img_tensor = transform(img_rgb_uint8).unsqueeze(0)
    label_tensor = torch.tensor([0])  # placeholder, non e' un attacco targeted

    model_wrapped = PipelinePredictWrapper("vgg16", model)
    attacker = SimBA(model_wrapped, dataset=None, image_size=IMG_SIZE)

    pred_orig, probs_orig = get_prediction_details("vgg16", model, class_names, img_tensor)

    adv_batch, probs, succs, queries, l2, linf = attacker.simba_batch(
        img_tensor,
        label_tensor,
        max_iters=max_iters,
        freq_dims=freq_dims,
        stride=7,
        epsilon=epsilon,
        pixel_attack=pixel_attack,
        log_every=max_iters,  # log solo all'ultima iterazione (endpoint silenzioso)
    )
    adv_tensor = adv_batch[0]

    pred_adv, probs_adv = get_prediction_details("vgg16", model, class_names, adv_tensor.unsqueeze(0))

    success = pred_orig != pred_adv

    adv_img_np = (adv_tensor.cpu().numpy().transpose(1, 2, 0).clip(0, 1) * 255).astype(np.uint8)

    return {
        "adversarial_image_rgb": adv_img_np,
        "prediction_original": pred_orig,
        "probabilities_original": probs_orig,
        "prediction_adversarial": pred_adv,
        "probabilities_adversarial": probs_adv,
        "success": success,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SimBA Attack on VGG16 (backend BPA)")
    parser.add_argument("--image_path", type=str, required=True)
    parser.add_argument("--model_path", type=str, default=DEFAULT_MODEL_PATH)
    parser.add_argument("--epsilon", type=float, default=0.2)
    parser.add_argument("--freq_dims", type=int, default=20)
    parser.add_argument("--pixel_attack", action="store_true")
    parser.add_argument("--max_iters", type=int, default=500, help="Numero di iterazioni (default 500)")
    args = parser.parse_args()

    output_dir = os.path.join("adversarial_image", "simba_vgg16")
    os.makedirs(output_dir, exist_ok=True)

    img = Image.open(args.image_path).convert("RGB")
    img_rgb_uint8 = np.array(img, dtype=np.uint8)

    print(f"Caricamento modello da {args.model_path}...")
    model = pipeline_predict.load_model("vgg16", args.model_path)
    class_names = pipeline_predict.get_class_names("vgg16", model)
    print(f"Classi ({len(class_names)}): {class_names}")

    result = run_simba_attack(
        img_rgb_uint8, model=model, class_names=class_names,
        epsilon=args.epsilon, freq_dims=args.freq_dims, pixel_attack=args.pixel_attack,
        max_iters=args.max_iters,
    )

    print_prediction_block("ORIGINALE", result["prediction_original"], result["probabilities_original"])
    print_prediction_block("AVVERSARIA", result["prediction_adversarial"], result["probabilities_adversarial"])
    print(f"  ESITO ATTACCO: {'SUCC' if result['success'] else 'FAIL'}")

    base_name = os.path.splitext(os.path.basename(args.image_path))[0]
    adv_bgr = cv2.cvtColor(result["adversarial_image_rgb"], cv2.COLOR_RGB2BGR)
    out_path = os.path.join(output_dir, f"{base_name}_adv.png")
    cv2.imwrite(out_path, adv_bgr)
    print(f"Salvato: {out_path}")