"""
Interfaccia COMUNE di interrogazione del modello, indipendente dal tipo
di modello (ML classico o VGG16). Smista internamente verso il modulo
specializzato giusto:
    model_type == "ml"     -> pipeline_predict_ml.py
    model_type == "vgg16"  -> pipeline_predict_vgg16.py

NOTA (adattamento per l'integrazione nel backend BPA): l'import di
pipeline_predict_ml e' reso opzionale. La pipeline dei modelli ML
classici richiede moduli aggiuntivi (image_train.*, con i filtri Otsu e
l'estrazione feature) non ancora portati in questo progetto - per ora
qui sono attivi solo gli attacchi su VGG16. Se pipeline_predict_ml.py
o le sue dipendenze non sono disponibili, model_type == "ml" solleva
un errore chiaro invece di far fallire l'import dell'intero modulo.
"""

import numpy as np

import pipeline_predict_vgg16 as _vgg16

try:
    import pipeline_predict_ml as _ml
    _ML_AVAILABLE = True
except ImportError:
    _ml = None
    _ML_AVAILABLE = False


def _require_ml():
    if not _ML_AVAILABLE:
        raise RuntimeError(
            "Pipeline ML classica non disponibile in questo backend: "
            "mancano i moduli image_train.* (filtri Otsu, estrazione feature). "
            "Solo model_type='vgg16' e' supportato al momento."
        )


def load_model(model_type: str, model_name_or_path: str):
    if model_type == "ml":
        _require_ml()
        return _ml.load_model(model_name_or_path)
    elif model_type == "vgg16":
        return _vgg16.load_model(model_name_or_path)
    else:
        raise ValueError(f"model_type non riconosciuto: {model_type}")


def get_class_names(model_type: str, model) -> list:
    if model_type == "ml":
        _require_ml()
        return _ml.get_class_names(model)
    elif model_type == "vgg16":
        return _vgg16.get_class_names()
    else:
        raise ValueError(f"model_type non riconosciuto: {model_type}")


def predict_proba_batch(img_rgb_batch: np.ndarray, model_type: str, model) -> np.ndarray:
    if model_type == "ml":
        _require_ml()
        return _ml.predict_proba_batch(img_rgb_batch, model)
    elif model_type == "vgg16":
        return _vgg16.predict_proba_batch(img_rgb_batch, model)
    else:
        raise ValueError(f"model_type non riconosciuto: {model_type}")
