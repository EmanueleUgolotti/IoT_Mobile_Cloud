import os
import sys
import argparse

import torch
import torch.nn as nn
import torchvision.transforms as transforms
import numpy as np
from PIL import Image

current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.append(current_dir)

from simba.simba import SimBA


IMG_SIZE = 512


class PredictFnWrapper(nn.Module):
    # Adatta una funzione predict_fn generica (numpy in, numpy out) a un
    # modulo PyTorch, cosi' SimBA (che lavora con tensori torch) puo'
    # chiamarla come se fosse un modello nn.Module qualsiasi.

    def __init__(self, predict_fn):
        super().__init__()
        self.predict_fn = predict_fn

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        img_np = x.detach().cpu().numpy().transpose(0, 2, 3, 1)
        img_rgb_255 = (img_np * 255.0).clip(0, 255)
        probs = self.predict_fn(img_rgb_255)
        return torch.tensor(probs).float()


def _local_predict_fn(model_type, model):
    # Predict_fn che usa un modello locale (richiede pipeline_predict/TensorFlow importati dal chiamante).
    import pipeline_predict  # import locale: solo se questa modalita' viene davvero usata

    def predict_fn(img_rgb_255_batch: np.ndarray) -> np.ndarray:
        return pipeline_predict.predict_proba_batch(img_rgb_255_batch, model_type, model)

    return predict_fn


def get_prediction_details(predict_fn, class_names, img_tensor):
    img_np = img_tensor.detach().cpu().numpy().transpose(0, 2, 3, 1)
    img_rgb_255 = (img_np * 255.0).clip(0, 255)
    probs = predict_fn(img_rgb_255)[0]
    pred_idx = int(np.argmax(probs))
    prediction = class_names[pred_idx]
    prob_dict = dict(zip(class_names, probs))
    return prediction, prob_dict


def print_prediction_block(label, prediction, prob_dict):
    print(f"  [{label}] -> {prediction}")
    for cls, prob in sorted(prob_dict.items(), key=lambda x: x[1], reverse=True):
        print(f"      - {cls:<18}: {prob:.2%}")


def run_simba_attack(img_rgb_uint8: np.ndarray, class_names, predict_fn=None,
                      model=None, model_type: str = "vgg16",
                      max_iters: int = 500, epsilon: float = 0.2,
                      freq_dims: int = 20, pixel_attack: bool = False):
    # Esegue l'attacco SimBA su una singola immagine.

    # Serve UNA delle due cose per interrogare il modello:
    # - predict_fn: funzione (img_rgb_255_batch: np.ndarray) -> probs np.ndarray,
    #   usata dal servizio robustness-simba (chiamata HTTP al servizio TF)
    # - model: modello gia' caricato in locale (richiede pipeline_predict/
    #  TensorFlow importabili nel processo corrente - solo per uso CLI/test)

    # Restituisce un dizionario con: immagine avversaria (uint8 RGB),
    # predizione originale, predizione avversaria, esito (SUCC/FAIL),
    # probabilita' per classe originali e avversarie.
    
    if predict_fn is None:
        if model is None:
            raise ValueError("Serve predict_fn oppure model")
        predict_fn = _local_predict_fn(model_type, model)

    transform = transforms.Compose([
        transforms.ToPILImage(),
        transforms.Resize((IMG_SIZE, IMG_SIZE)),
        transforms.ToTensor(),
    ])
    img_tensor = transform(img_rgb_uint8).unsqueeze(0)
    label_tensor = torch.tensor([0])  # placeholder, non e' un attacco targeted

    model_wrapped = PredictFnWrapper(predict_fn)
    attacker = SimBA(model_wrapped, dataset=None, image_size=IMG_SIZE)

    pred_orig, probs_orig = get_prediction_details(predict_fn, class_names, img_tensor)

    adv_batch, probs, succs, queries, l2, linf = attacker.simba_batch(
        img_tensor,
        label_tensor,
        max_iters=max_iters,
        freq_dims=freq_dims,
        stride=7,
        epsilon=epsilon,
        pixel_attack=pixel_attack,
        log_every=max_iters,  # log solo all'ultima iterazione
    )
    adv_tensor = adv_batch[0]

    pred_adv, probs_adv = get_prediction_details(predict_fn, class_names, adv_tensor.unsqueeze(0))

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
    parser = argparse.ArgumentParser(description="SimBA Attack (CLI, richiede TensorFlow in locale)")
    parser.add_argument("--image_path", type=str, required=True)
    parser.add_argument("--model_path", type=str, default="models/best_model.keras")
    parser.add_argument("--epsilon", type=float, default=0.2)
    parser.add_argument("--freq_dims", type=int, default=20)
    parser.add_argument("--pixel_attack", action="store_true")
    parser.add_argument("--max_iters", type=int, default=500, help="Numero di iterazioni (default 500)")
    args = parser.parse_args()

    # Import locale: solo la modalita' CLI ne ha bisogno (richiede TensorFlow
    # installato nello stesso ambiente - va bene sul PC nudo, non dentro
    # il container robustness-simba, che non lo include)
    import pipeline_predict

    output_dir = os.path.join("adversarial_image", "simba_vgg16")
    os.makedirs(output_dir, exist_ok=True)

    img = Image.open(args.image_path).convert("RGB")
    img_rgb_uint8 = np.array(img, dtype=np.uint8)

    print(f"Caricamento modello da {args.model_path}...")
    model = pipeline_predict.load_model("vgg16", args.model_path)
    class_names = pipeline_predict.get_class_names("vgg16", model)
    print(f"Classi ({len(class_names)}): {class_names}")

    result = run_simba_attack(
        img_rgb_uint8, class_names, model=model,
        epsilon=args.epsilon, freq_dims=args.freq_dims, pixel_attack=args.pixel_attack,
        max_iters=args.max_iters,
    )

    print_prediction_block("ORIGINALE", result["prediction_original"], result["probabilities_original"])
    print_prediction_block("AVVERSARIA", result["prediction_adversarial"], result["probabilities_adversarial"])
    print(f"  ESITO ATTACCO: {'SUCC' if result['success'] else 'FAIL'}")

    base_name = os.path.splitext(os.path.basename(args.image_path))[0]
    adv_img = Image.fromarray(result["adversarial_image_rgb"], mode="RGB")
    out_path = os.path.join(output_dir, f"{base_name}_adv.png")
    adv_img.save(out_path)
    print(f"Salvato: {out_path}")
