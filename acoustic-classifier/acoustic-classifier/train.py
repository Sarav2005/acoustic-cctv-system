"""
Trains a small CNN on the log-mel spectrograms produced by preprocess.py,
evaluates it with a confusion matrix, and saves both:
  - a full Keras model (models/acoustic_classifier.keras) for use on your laptop
  - a converted TFLite model (models/acoustic_classifier.tflite) which is the
    format you'll eventually flash onto the ESP32 via TFLite Micro

The architecture is intentionally small (a handful of conv layers, ~50-150k
parameters) rather than a huge network, for two reasons:
  1. With a few thousand training clips at most, a huge model will just
     memorize the training set instead of generalizing (overfitting).
  2. It needs to eventually run on an ESP32's very limited RAM/flash --
     designing for that constraint from day one avoids a "shrink it later"
     rewrite.

Usage:
    python train.py
"""

import numpy as np
import tensorflow as tf
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.utils.class_weight import compute_class_weight

from config import FEATURES_NPZ, MODELS_DIR, KERAS_MODEL_PATH, TFLITE_MODEL_PATH, CLASSES

import os


def build_model(input_shape, num_classes):
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=input_shape),
        # Add a channel dimension: (N_MELS, N_FRAMES) -> (N_MELS, N_FRAMES, 1)
        tf.keras.layers.Reshape((*input_shape, 1)),

        tf.keras.layers.Conv2D(8, (3, 3), activation="relu", padding="same"),
        tf.keras.layers.MaxPooling2D((2, 2)),
        tf.keras.layers.BatchNormalization(),

        tf.keras.layers.Conv2D(16, (3, 3), activation="relu", padding="same"),
        tf.keras.layers.MaxPooling2D((2, 2)),
        tf.keras.layers.BatchNormalization(),

        tf.keras.layers.Conv2D(32, (3, 3), activation="relu", padding="same"),
        tf.keras.layers.GlobalAveragePooling2D(),

        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(num_classes, activation="softmax"),
    ])

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def main():
    if not os.path.isfile(FEATURES_NPZ):
        print(f"{FEATURES_NPZ} not found -- run preprocess.py first.")
        return

    data = np.load(FEATURES_NPZ, allow_pickle=True)
    X, y = data["X"], data["y"]
    classes = list(data["classes"])

    print(f"Loaded {X.shape[0]} samples, shape {X.shape[1:]}, classes: {classes}")

    # Normalize: log-mel values are typically in [-80, 0] dB. Scaling to
    # roughly [0, 1] helps the network train faster and more stably.
    X = (X + 80.0) / 80.0
    X = np.clip(X, 0.0, 1.0)

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    # Class weighting compensates for imbalanced classes (e.g. footsteps
    # having far fewer samples than car/bike from the MVD dataset) by making
    # mistakes on rare classes "cost" more during training.
    class_weights = compute_class_weight("balanced", classes=np.unique(y_train), y=y_train)
    class_weight_dict = {i: w for i, w in enumerate(class_weights)}
    print(f"Class weights: {class_weight_dict}")

    model = build_model(input_shape=X.shape[1:], num_classes=len(classes))
    model.summary()

    os.makedirs(MODELS_DIR, exist_ok=True)

    callbacks = [
        tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=8, restore_best_weights=True),
        tf.keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=4),
    ]

    history = model.fit(
        X_train, y_train,
        validation_split=0.15,
        epochs=60,
        batch_size=32,
        class_weight=class_weight_dict,
        callbacks=callbacks,
        verbose=2,
    )

    # --- Evaluate ---
    y_pred = np.argmax(model.predict(X_test), axis=1)

    print("\nClassification report (test set):")
    print(classification_report(y_test, y_pred, target_names=classes))

    print("Confusion matrix (rows = actual, columns = predicted):")
    print(classes)
    print(confusion_matrix(y_test, y_pred))

    # --- Save Keras model ---
    model.save(KERAS_MODEL_PATH)
    print(f"\nSaved Keras model to {KERAS_MODEL_PATH}")

    # --- Convert to TFLite (for later ESP32 deployment via TFLite Micro) ---
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()

    with open(TFLITE_MODEL_PATH, "wb") as f:
        f.write(tflite_model)

    tflite_size_kb = len(tflite_model) / 1024
    print(f"Saved TFLite model to {TFLITE_MODEL_PATH} ({tflite_size_kb:.1f} KB)")

    if tflite_size_kb > 200:
        print("Note: this is on the larger side for an ESP32 (limited flash/RAM). "
              "If it doesn't fit later, options include: fewer filters per conv "
              "layer, int8 quantization (already partially applied via DEFAULT "
              "optimizations), or fewer mel bins in config.py.")


if __name__ == "__main__":
    main()
