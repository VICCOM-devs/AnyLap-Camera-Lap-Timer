"""The AnyLap car-signature network: a small MobileNet-style CNN (~0.9 M parameters) that turns a 64 x 64 crop
of a car at the line into a 64-number signature, plus two helper heads used by the engine:
  - count: 0 = no whole car at the centre, 1 = one car, 2 = two or more (touching cars)
  - nose:  where the subject car's nose is, relative to the crop centre (in crop widths)
Identity is decided by comparing signatures (cosine) with the session's calibrated cars – nothing is trained
per user. Training uses an ArcFace head over the library's car ids (dropped at export).
"""
import math
import torch
import torch.nn as nn
import torch.nn.functional as F

EMB = 64

def _heads(net, f):
    """The three heads in full precision even under GPU autocast: the signature is a 64-number direction compared
    by cosine, and half precision in its last layers / normalisation can stall identity learning (suspected cause
    of run 1's flat identity loss on the RTX 3060 Ti; not reproducible on CPU)."""
    dev = f.device.type
    with torch.autocast(device_type=dev, enabled=False):
        f = f.float()
        e = F.normalize(net.emb(f), dim=1, eps=1e-6)
        return e, net.count(f), net.nose(f)

class ConvBNAct(nn.Sequential):
    def __init__(self, cin, cout, k=3, s=1, groups=1, act=True):
        super().__init__(nn.Conv2d(cin, cout, k, s, k // 2, groups=groups, bias=False), nn.BatchNorm2d(cout), nn.Hardswish() if act else nn.Identity())

class SE(nn.Module):
    def __init__(self, c, r=4):
        super().__init__(); self.fc1 = nn.Conv2d(c, c // r, 1); self.fc2 = nn.Conv2d(c // r, c, 1)
    def forward(self, x):
        s = F.adaptive_avg_pool2d(x, 1); s = F.relu(self.fc1(s)); s = F.hardsigmoid(self.fc2(s)); return x * s

class InvRes(nn.Module):
    """MobileNetV3 inverted residual: expand 1x1 -> depthwise kxk -> (SE) -> project 1x1, skip when shapes match."""
    def __init__(self, cin, cout, exp, k=3, s=1, se=False):
        super().__init__()
        self.skip = s == 1 and cin == cout
        layers = []
        if exp != cin: layers.append(ConvBNAct(cin, exp, 1))
        layers.append(ConvBNAct(exp, exp, k, s, groups=exp))
        if se: layers.append(SE(exp))
        layers.append(ConvBNAct(exp, cout, 1, act=False))
        self.block = nn.Sequential(*layers)
    def forward(self, x):
        y = self.block(x); return x + y if self.skip else y

class CarNet(nn.Module):
    def __init__(self, emb=EMB, width=1.0):
        super().__init__()
        c = lambda n: max(8, int(n * width))
        self.stem = ConvBNAct(3, c(24), 3, 2)                       # 64 -> 32
        self.blocks = nn.Sequential(
            InvRes(c(24), c(24), c(48), 3, 1),
            InvRes(c(24), c(40), c(96), 3, 2, se=True),              # 32 -> 16
            InvRes(c(40), c(40), c(120), 3, 1, se=True),
            InvRes(c(40), c(64), c(160), 5, 2, se=True),             # 16 -> 8
            InvRes(c(64), c(64), c(192), 5, 1, se=True),
            InvRes(c(64), c(96), c(288), 3, 2, se=True),             # 8 -> 4
            InvRes(c(96), c(96), c(384), 3, 1, se=True),
            InvRes(c(96), c(128), c(384), 3, 1, se=True),
        )
        self.last = ConvBNAct(c(128), c(256), 1)
        self.emb = nn.Sequential(nn.Linear(c(256), 128), nn.Hardswish(), nn.Linear(128, emb))
        self.count = nn.Linear(c(256), 3)
        self.nose = nn.Linear(c(256), 2)
        for m in self.modules():
            if isinstance(m, nn.Conv2d): nn.init.kaiming_normal_(m.weight, mode="fan_out")
            elif isinstance(m, nn.Linear): nn.init.normal_(m.weight, 0, 0.01); nn.init.zeros_(m.bias)

    def features(self, x):
        x = self.last(self.blocks(self.stem(x)))
        return F.adaptive_avg_pool2d(x, 1).flatten(1)

    def forward(self, x):
        """x: N x 3 x H x W in 0..1. Returns (signature L2-normalised, count logits, nose offset)."""
        x = (x - 0.45) / 0.25
        f = self.features(x)
        return _heads(self, f)

    def heads(self):
        return list(self.emb.parameters()) + list(self.count.parameters()) + list(self.nose.parameters())

class ArcFace(nn.Module):
    """Additive angular margin softmax over the library's identities (training only). Always computed in float32
    (outside autocast), and the margin `m` is set by the trainer every step: 0 at the start (a plain cosine
    softmax that converges easily), eased in to its final value – a full margin from step 0 with hundreds of
    classes stalls (run 1, Oct 31: identity loss flat at 4.94, naming at chance)."""
    def __init__(self, emb, n_classes, s=30.0, m=0.3):
        super().__init__()
        self.w = nn.Parameter(torch.empty(n_classes, emb)); nn.init.xavier_uniform_(self.w)
        self.s, self.m = s, m
    def forward(self, e, y):
        e = F.normalize(e.float(), dim=1)
        cos = F.linear(e, F.normalize(self.w.float(), dim=1)).clamp(-1 + 1e-6, 1 - 1e-6)
        if self.m > 0:
            sin = torch.sqrt((1.0 - cos * cos).clamp(min=1e-6))
            phi = cos * math.cos(self.m) - sin * math.sin(self.m)          # cos(theta + m)
            phi = torch.where(cos > math.cos(math.pi - self.m), phi, cos - math.sin(math.pi - self.m) * self.m)   # keep it monotonic
            onehot = F.one_hot(y.clamp(min=0), cos.shape[1]).bool()
            cos = torch.where(onehot, phi, cos)
        return cos * self.s

# ---------------------------------------------------------------- pretrained backbones
IMAGENET_MEAN = (0.485, 0.456, 0.406); IMAGENET_STD = (0.229, 0.224, 0.225)

class PretrainedNet(nn.Module):
    """An ImageNet-pretrained phone backbone (EfficientNet-Lite0 via timm, or MobileNetV3 via torchvision) with the
    same three heads as CarNet: L2 signature, count (0 / 1 / 2+ cars), nose offset. Same outputs, same export."""
    def __init__(self, name="effnet_lite0", emb=EMB, pretrained=True):
        super().__init__()
        self.name = name
        if name == "effnet_lite0":
            import timm
            self.backbone = timm.create_model("efficientnet_lite0", pretrained=pretrained, num_classes=0)
            nf = self.backbone.num_features
        elif name in ("mnv3_large", "mnv3_small"):
            import torchvision
            ctor = torchvision.models.mobilenet_v3_large if name == "mnv3_large" else torchvision.models.mobilenet_v3_small
            w = "DEFAULT" if pretrained else None
            m = ctor(weights=w)
            self.backbone = nn.Sequential(m.features, nn.AdaptiveAvgPool2d(1), nn.Flatten())
            nf = m.classifier[0].in_features
        else:
            raise ValueError(name)
        self.register_buffer("mean", torch.tensor(IMAGENET_MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(IMAGENET_STD).view(1, 3, 1, 1))
        self.emb = nn.Sequential(nn.Linear(nf, 256), nn.Hardswish(), nn.Linear(256, emb))
        self.count = nn.Linear(nf, 3)
        self.nose = nn.Linear(nf, 2)
        for m in (self.emb, self.count, self.nose):
            for l in (m if isinstance(m, nn.Sequential) else [m]):
                if isinstance(l, nn.Linear): nn.init.normal_(l.weight, 0, 0.01); nn.init.zeros_(l.bias)

    def heads(self):
        return list(self.emb.parameters()) + list(self.count.parameters()) + list(self.nose.parameters())

    def forward(self, x):
        f = self.backbone((x - self.mean) / self.std)
        return _heads(self, f)

BACKBONES = ["carnet", "effnet_lite0", "mnv3_large", "mnv3_small"]

def build_model(backbone="carnet", width=1.0, pretrained=True):
    if backbone == "carnet": return CarNet(width=width)
    return PretrainedNet(backbone, pretrained=pretrained)

def from_checkpoint(ck):
    """Model for a saved checkpoint (old checkpoints have no backbone field = CarNet)."""
    net = build_model(ck.get("backbone", "carnet"), ck.get("width", 1.0), pretrained=False)
    net.load_state_dict(ck["model"]); return net

def count_params(m): return sum(p.numel() for p in m.parameters())

if __name__ == "__main__":
    net = CarNet()
    x = torch.rand(2, 3, 64, 64)
    e, c, n = net(x)
    print("params", count_params(net), "emb", e.shape, "count", c.shape, "nose", n.shape)
