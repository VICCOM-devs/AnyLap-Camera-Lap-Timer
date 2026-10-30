"""Export a trained checkpoint for the phone.

    python tools/ml/export/export.py D:/anylap-ml/runs/v1/best.pt --out D:/anylap-ml/export/v1

Writes:
  carnet.onnx            – the network without the ArcFace head: input 1x3xHxW float 0..1, outputs signature (64), count (3), nose (2)
  carnet.tflite          – LiteRT model (needs `pip install ai-edge-torch`; float32 by default, --int8 quantises with composed samples)
  carnet.json            – size, embedding dim, sha256 of each file, the checkpoint's metrics (what goes into anylap_models)
The app side (Android LiteRT / iOS Core ML via expect/actual) comes with the model; see tools/ml/README.md.
"""
import argparse, hashlib, json, os, sys
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "train")); sys.path.insert(0, os.path.join(HERE, "..", "synth"))
from model import CarNet

def sha256(p):
    h = hashlib.sha256(); h.update(open(p, "rb").read()); return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("checkpoint"); ap.add_argument("--out", required=True)
    ap.add_argument("--int8", action="store_true", help="post-training int8 quantisation with composed samples (needs --sprites)")
    ap.add_argument("--sprites", default=None); ap.add_argument("--version", type=int, default=1)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    ck = torch.load(a.checkpoint, map_location="cpu", weights_only=False)
    size = ck.get("size", 64)
    net = CarNet(width=ck.get("width", 1.0)); net.load_state_dict(ck["model"]); net.eval()
    x = torch.rand(1, 3, size, size)
    onnx_path = os.path.join(a.out, "carnet.onnx")
    torch.onnx.export(net, x, onnx_path, input_names=["image"], output_names=["signature", "count", "nose"], opset_version=17, dynamo=False)
    files = {"carnet.onnx": sha256(onnx_path)}
    tfl = None
    try:
        import ai_edge_torch
        quant = None
        if a.int8 and a.sprites:
            from compose import SpriteDataset
            import numpy as np
            from ai_edge_torch.quantize import pt2e_quantizer, quant_config
            ds = SpriteDataset(a.sprites, ids=ck.get("train_ids"), size=size); rng = np.random.default_rng(0)
            def rep():
                for _ in range(64):
                    img, _ = ds.sample(rng); yield (torch.from_numpy(img.transpose(2, 0, 1)[None]).float() / 255,)
            quantizer = pt2e_quantizer.PT2EQuantizer().set_global(pt2e_quantizer.get_symmetric_quantization_config(is_per_channel=True))
            quant = quant_config.QuantConfig(pt2e_quantizer=quantizer)
            edge = ai_edge_torch.convert(net, (x,), quant_config=quant)
        else:
            edge = ai_edge_torch.convert(net, (x,))
        tfl = os.path.join(a.out, "carnet.tflite"); edge.export(tfl); files["carnet.tflite"] = sha256(tfl)
    except ImportError:
        print("ai_edge_torch not installed: ONNX only (pip install ai-edge-torch for the .tflite)")
    meta = dict(name="signature", version=a.version, input=[1, 3, size, size], embedding=64, outputs=["signature", "count", "nose"],
                files=files, bytes={k: os.path.getsize(os.path.join(a.out, k)) for k in files}, metrics=ck.get("metrics"), step=ck.get("step"))
    json.dump(meta, open(os.path.join(a.out, "carnet.json"), "w"), indent=1)
    print(json.dumps(meta, indent=1))

if __name__ == "__main__":
    main()
