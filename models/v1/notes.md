Car recognition model v1 (signature net) for AnyLap 0.5.0+ (build ≥ 1008).

- Run `effnet_lite0_3h`: EfficientNet-Lite0, 96 px input, 3 h on 974 synthetic cars.
- Unseen cars named right 80.9 % (8-car races), training cars 86.1 %, intruders called a car 6.4 %, count 96.1 %.
- `carnet.tflite` = LiteRT float32, input 1×96×96×3 (0..1), outputs signature 64 / count 3 / nose 2. Converted from `carnet.onnx` with onnx2tf, outputs identical.
- Phones pick it up from the `anylap_models` row and verify the sha256. Shadow mode: tie-breaker only.
