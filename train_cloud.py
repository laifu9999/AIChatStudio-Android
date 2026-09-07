# -*- coding: utf-8 -*-
"""乐乐修图 · 祛斑多引擎同步训练（云端 GPU 一键版，单文件自包含）

用法（AutoDL / 任何有 CUDA 的机器）:
    pip install torch numpy opencv-python-headless
    python train_cloud.py --data-dir . --steps 1500 --batch 16
    （把 优化1.jpg 优化2.jpg 和本脚本放同一目录）

产出 output/ 目录:
    spot_fix_unet.pt       权重（下载回本地覆盖 models/spot_fix_unet.pt）
    spot_fix_unet.onnx     部署模型（可选下载，本地也能从 .pt 导出）
    preview_ours.jpg       整图修复预览（先看这个再决定要不要下载）

本地导入：把 output/spot_fix_unet.pt 覆盖到应用 models/ 目录即可，
ONNX 会由应用在下次训练/导出时自动刷新（或直接用云端的 onnx）。

架构：多头 U-Net 多引擎同步训练
  mask 头 = 祛斑引擎（斑点概率图）
  rgb  头 = 修复引擎（端到端学美图五合一手法）
  tone 头 = 色调引擎（提亮/减红/匀光残差场）
"""
import os
import argparse
import numpy as np
import cv2

import torch
import torch.nn as nn

PATCH = 256
TONE_SCALE = 0.12
ORIG_NAME = "优化1.jpg"
FIXED_NAME = "优化2.jpg"


# ================================================================ 网络定义
def build_unet(in_ch=3, base=16):
    class ConvBlock(nn.Module):
        def __init__(self, ci, co):
            super().__init__()
            self.net = nn.Sequential(
                nn.Conv2d(ci, co, 3, padding=1), nn.ReLU(inplace=True),
                nn.Conv2d(co, co, 3, padding=1), nn.ReLU(inplace=True))

        def forward(self, x):
            return self.net(x)

    class UNetMulti(nn.Module):
        def __init__(self):
            super().__init__()
            b = base
            self.e1 = ConvBlock(in_ch, b)
            self.e2 = ConvBlock(b, b * 2)
            self.e3 = ConvBlock(b * 2, b * 4)
            self.pool = nn.MaxPool2d(2)
            self.mid = ConvBlock(b * 4, b * 8)
            self.u3 = nn.ConvTranspose2d(b * 8, b * 4, 2, stride=2)
            self.d3 = ConvBlock(b * 8, b * 4)
            self.u2 = nn.ConvTranspose2d(b * 4, b * 2, 2, stride=2)
            self.d2 = ConvBlock(b * 4, b * 2)
            self.u1 = nn.ConvTranspose2d(b * 2, b, 2, stride=2)
            self.d1 = ConvBlock(b * 2, b)
            self.head_mask = nn.Conv2d(b, 1, 1)
            self.head_rgb = nn.Conv2d(b, 3, 1)
            self.head_tone = nn.Conv2d(b, 3, 1)

        def forward(self, x):
            x1 = self.e1(x)
            x2 = self.e2(self.pool(x1))
            x3 = self.e3(self.pool(x2))
            m = self.mid(self.pool(x3))
            y3 = self.d3(torch.cat([self.u3(m), x3], 1))
            y2 = self.d2(torch.cat([self.u2(y3), x2], 1))
            y1 = self.d1(torch.cat([self.u1(y2), x1], 1))
            return {"mask": self.head_mask(y1),
                    "rgb": self.head_rgb(y1),
                    "tone": torch.tanh(self.head_tone(y1)) * TONE_SCALE}

    return UNetMulti()


# ================================================================ 数据
def imread_cn(p):
    return cv2.imdecode(np.fromfile(p, dtype=np.uint8), cv2.IMREAD_COLOR)


def lowpass(x, sigma=8.0):
    return cv2.GaussianBlur(x, (0, 0), sigma)


class Gen:
    def __init__(self, data_dir, seed=0):
        self.rng = np.random.default_rng(seed)
        orig = cv2.cvtColor(imread_cn(os.path.join(data_dir, ORIG_NAME)),
                            cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        fixed = cv2.cvtColor(imread_cn(os.path.join(data_dir, FIXED_NAME)),
                             cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        assert orig.shape == fixed.shape
        self.orig, self.fixed = orig, fixed
        self.h, self.w = orig.shape[:2]

        # 差分标签（斑点）
        lo = cv2.cvtColor((orig * 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)
        lf = cv2.cvtColor((fixed * 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)
        dL = lo[..., 0] - lf[..., 0]
        dA = lo[..., 1] - lf[..., 1]
        dLhp = dL - cv2.GaussianBlur(dL, (0, 0), 5.0)
        dAhp = dA - cv2.GaussianBlur(dA, (0, 0), 5.0)
        label = np.clip((dLhp - 1.5) / 6.0, 0, 1) * np.clip((dAhp + 1.0) / 3.0, 0, 1)
        label = np.clip(label * 2.2, 0, 1)
        lab_u8 = cv2.morphologyEx((label * 255).astype(np.uint8),
                                  cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
        self.label = lab_u8.astype(np.float32) / 255.0

        # 真实雀斑精灵
        self.sprites = []
        n_lbl, _, st, _ = cv2.connectedComponentsWithStats((self.label > 0.18).astype(np.uint8), 8)
        for i in range(1, n_lbl):
            x0, y0, bw, bh, area = st[i]
            if bw > 24 or bh > 24 or area < 2:
                continue
            pad = 2
            xa, ya = max(0, x0 - pad), max(0, y0 - pad)
            sp_rgb = orig[ya:y0 + bh + pad, xa:x0 + bw + pad].copy()
            sp_a = np.clip(self.label[ya:y0 + bh + pad, xa:x0 + bw + pad] * 1.5, 0, 1).copy()
            if sp_rgb.shape[0] < 3 or sp_rgb.shape[1] < 3:
                continue
            self.sprites.append((sp_rgb, sp_a))
        print("真实雀斑精灵: %d 个" % len(self.sprites))

        # 干净底图皮肤区
        labf = cv2.cvtColor((fixed * 255).astype(np.uint8), cv2.COLOR_RGB2LAB)
        skinm = ((labf[..., 1] > 126) & (labf[..., 1] < 162) &
                 (labf[..., 2] > 130) & (labf[..., 2] < 165) &
                 (labf[..., 0] > 90)).astype(np.uint8)
        self.skinm = cv2.dilate(skinm, np.ones((5, 5), np.uint8))
        self.tone_field = lowpass(fixed - orig)
        print("数据就绪: %dx%d" % (self.w, self.h))

    def _jitter(self, rgb):
        r = self.rng
        out = rgb.copy()
        out += r.uniform(-0.05, 0.05)
        out = (out - 0.5) * r.uniform(0.93, 1.07) + 0.5
        out *= r.uniform(0.96, 1.04, size=(3, 1, 1)).astype(np.float32)
        return np.clip(out, 0.0, 1.0)

    def _paste_sprites(self, patch, k):
        out = patch.copy()
        pm = np.zeros((PATCH, PATCH), np.float32)
        ys, xs = np.where(self.skinm[:self.h, :self.w] > 0)
        if len(ys) == 0:
            return out, pm
        for _ in range(k):
            sp_rgb, sp_a = self.sprites[int(self.rng.integers(0, len(self.sprites)))]
            sh, sw = sp_a.shape[:2]
            if sh >= PATCH or sw >= PATCH:
                continue
            for _try in range(6):
                y = int(ys[int(self.rng.integers(0, len(ys)))]) - sh // 2
                x = int(xs[int(self.rng.integers(0, len(xs)))]) - sw // 2
                if 0 <= y and y + sh <= PATCH and 0 <= x and x + sw <= PATCH:
                    a = sp_a * self.rng.uniform(0.7, 1.1)
                    out[y:y + sh, x:x + sw] = out[y:y + sh, x:x + sw] * (1 - a[..., None]) + sp_rgb * a[..., None]
                    pm[y:y + sh, x:x + sw] = np.maximum(pm[y:y + sh, x:x + sw], a)
                    break
        return out, pm

    def _big_blob(self, patch):
        out = patch.copy()
        r = int(self.rng.integers(12, 30))
        y = int(self.rng.integers(r + 2, PATCH - r - 2))
        x = int(self.rng.integers(r + 2, PATCH - r - 2))
        a = np.zeros((PATCH, PATCH), np.float32)
        cv2.circle(a, (x, y), r, self.rng.uniform(0.10, 0.28), -1)
        a = cv2.GaussianBlur(a, (0, 0), r * 0.45)[..., None]
        out = out * (1 - a) + out * self.rng.uniform(0.45, 0.7) * a
        return out

    def sample(self, n):
        imgs = np.zeros((n, 3, PATCH, PATCH), np.float32)
        tgts = np.zeros_like(imgs)
        masks = np.zeros((n, 1, PATCH, PATCH), np.float32)
        tones = np.zeros_like(imgs)
        for i in range(n):
            y = int(self.rng.integers(0, self.h - PATCH + 1))
            x = int(self.rng.integers(0, self.w - PATCH + 1))
            r = self.rng.random()
            if r < 0.38:
                raw = self.orig[y:y + PATCH, x:x + PATCH].transpose(2, 0, 1)
                imgs[i] = self._jitter(raw)
                tgts[i] = self.fixed[y:y + PATCH, x:x + PATCH].transpose(2, 0, 1)
                masks[i, 0] = self.label[y:y + PATCH, x:x + PATCH]
                tfl = lowpass(self.tone_field[y:y + PATCH, x:x + PATCH]).transpose(2, 0, 1)
                tones[i] = np.clip(tfl / TONE_SCALE, -1.0, 1.0)
            elif r < 0.73:
                base = self.fixed[y:y + PATCH, x:x + PATCH]
                tgts[i] = base.transpose(2, 0, 1)
                pasted, pm = self._paste_sprites(base, int(self.rng.integers(10, 60)))
                masks[i, 0] = pm
                imgs[i] = self._jitter(pasted.transpose(2, 0, 1))
            elif r < 0.88:
                base = self.fixed[y:y + PATCH, x:x + PATCH]
                tgts[i] = base.transpose(2, 0, 1)
                imgs[i] = self._jitter(base.transpose(2, 0, 1))
            else:
                base = self.fixed[y:y + PATCH, x:x + PATCH]
                blob = self._big_blob(base)
                tgts[i] = blob.transpose(2, 0, 1)
                imgs[i] = self._jitter(blob.transpose(2, 0, 1))
        return imgs, tgts, masks, tones


# ================================================================ 训练
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=".")
    ap.add_argument("--steps", type=int, default=1500)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--init", default="", help="可选：已有 .pt 热启动")
    a = ap.parse_args()

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    print("设备:", dev, "|", torch.cuda.get_device_name(0) if dev == "cuda" else "CPU")
    torch.manual_seed(0)
    np.random.seed(0)

    net = build_unet().to(dev)
    if a.init and os.path.exists(a.init):
        sd = torch.load(a.init, map_location=dev)
        msd = net.state_dict()
        fit = {k: v for k, v in sd.items() if k in msd and msd[k].shape == v.shape}
        net.load_state_dict(fit, strict=False)
        print("热启动: 迁移 %d 键" % len(fit))

    gen = Gen(a.data_dir)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr)
    sig = nn.Sigmoid()
    bce = nn.BCEWithLogitsLoss(pos_weight=torch.tensor(15.0, device=dev))

    import time
    t0 = time.time()
    net.train()
    for it in range(a.steps):
        imgs, tgts, masks, tones = gen.sample(a.batch)
        x = torch.from_numpy(imgs).to(dev)
        t = torch.from_numpy(tgts).to(dev)
        m = torch.from_numpy(masks).to(dev)
        tn = torch.from_numpy(tones).to(dev)
        opt.zero_grad()
        out = net(x)
        rgb = sig(out["rgb"])
        l1 = torch.abs(rgb - t)
        wmap = 1.0 + 6.0 * torch.clamp(torch.abs(x - t) * 10.0, 0.0, 1.0)
        l1 = (l1 * wmap).mean()
        g = (torch.abs(rgb[..., 1:] - rgb[..., :-1]) - torch.abs(t[..., 1:] - t[..., :-1])).abs().mean() \
            + (torch.abs(rgb[..., 1:, :] - rgb[..., :-1, :]) - torch.abs(t[..., 1:, :] - t[..., :-1, :])).abs().mean()
        lm = bce(out["mask"], m)
        lt = torch.abs(out["tone"] - tn).mean()
        loss = l1 + 0.3 * g + 0.5 * lm + 0.2 * lt
        loss.backward()
        opt.step()
        if (it + 1) % 100 == 0:
            spd = (it + 1) / (time.time() - t0)
            print("step %d/%d rgb=%.4f grad=%.4f mask=%.4f tone=%.4f (%.1f it/s)"
                  % (it + 1, a.steps, float(l1), float(g), float(lm), float(lt), spd))

    os.makedirs("output", exist_ok=True)
    torch.save(net.state_dict(), "output/spot_fix_unet.pt")

    # 整图预览
    net.eval()
    with torch.no_grad():
        rgb_in = gen.orig.transpose(2, 0, 1)[None]
        h, w = gen.orig.shape[:2]
        ph, pw = (16 - h % 16) % 16, (16 - w % 16) % 16
        x = torch.from_numpy(np.pad(rgb_in, ((0, 0), (0, 0), (0, ph), (0, pw)))).to(dev)
        y = torch.sigmoid(net(x)["rgb"])[0].cpu().numpy()[:, :h, :w].transpose(1, 2, 0)
    fix_bgr = cv2.cvtColor((np.clip(y, 0, 1) * 255 + 0.5).astype(np.uint8), cv2.COLOR_RGB2BGR)
    cv2.imencode(".jpg", fix_bgr, [cv2.IMWRITE_JPEG_QUALITY, 95])[1].tofile("output/preview_ours.jpg")

    try:
        torch.onnx.export(
            net.cpu(), torch.zeros(1, 3, 256, 256), "output/spot_fix_unet.onnx",
            opset_version=13, input_names=["image"],
            output_names=["mask", "rgb", "tone"],
            dynamic_axes={"image": {2: "h", 3: "w"}, "mask": {2: "h", 3: "w"},
                          "rgb": {2: "h", 3: "w"}, "tone": {2: "h", 3: "w"}})
    except Exception as e:
        print("ONNX 导出失败(可忽略, 本地可从 .pt 导出):", e)

    print("完成! 总耗时 %.1f 分钟" % ((time.time() - t0) / 60))
    print("下载 output/spot_fix_unet.pt (和 spot_fix_unet.onnx) 回本地覆盖 models/")


if __name__ == "__main__":
    main()
