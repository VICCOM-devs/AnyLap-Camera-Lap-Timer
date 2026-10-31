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
        e = F.normalize(self.emb(f), dim=1)
        return e, self.count(f), self.nose(f)

class ArcFace(nn.Module):
    """Additive angular margin softmax over the library's identities (training only)."""
    def __init__(self, emb, n_classes, s=30.0, m=0.3):
        super().__init__()
        self.w = nn.Parameter(torch.empty(n_classes, emb)); nn.init.xavier_uniform_(self.w)
        self.s, self.m = s, m
    def forward(self, e, y):
        cos = F.linear(e, F.normalize(self.w, dim=1)).clamp(-1 + 1e-6, 1 - 1e-6)
        theta = torch.acos(cos)
        target = torch.cos(theta + self.m)
        onehot = F.one_hot(y.clamp(min=0), cos.shape[1]).bool()
        logits = torch.where(onehot, target, cos) * self.s
        return logits

def count_params(m): return sum(p.numel() for p in m.parameters())

if __name__ == "__main__":
    net = CarNet()
    x = torch.rand(2, 3, 64, 64)
    e, c, n = net(x)
    print("params", count_params(net), "emb", e.shape, "count", c.shape, "nose", n.shape)
