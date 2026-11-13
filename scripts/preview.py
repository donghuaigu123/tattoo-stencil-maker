#!/usr/bin/env python3
"""
桌面端效果预览 / 算法参考实现。

本脚本与 app/src/main/java/.../StencilProcessor.java 的处理步骤一一对应，
用于在电脑上无 Android 环境时复现 App 的输出、调参或批量生成线稿。

依赖：numpy, opencv-python (仅用于图片读写与连通域标记)
用法：python3 scripts/preview.py samples/01-input.jpg samples/03-result.png
"""
import sys
import numpy as np
import cv2

MAX_SIDE = 1440
DOWN_FACTOR = 8


def box_h(src, r):
    h, w = src.shape
    p = np.pad(src, ((0, 0), (r, r)), mode='edge').astype(np.float64)
    c = np.concatenate([np.zeros((h, 1)), np.cumsum(p, axis=1)], axis=1)
    return ((c[:, 2 * r + 1:] - c[:, :w]) / (2 * r + 1)).astype(np.float32)


def box_v(src, r):
    h, w = src.shape
    p = np.pad(src, ((r, r), (0, 0)), mode='edge').astype(np.float64)
    c = np.concatenate([np.zeros((1, w)), np.cumsum(p, axis=0)], axis=0)
    return ((c[2 * r + 1:, :] - c[:h, :]) / (2 * r + 1)).astype(np.float32)


def gauss(src, r, passes=3):
    out = src.copy()
    for _ in range(passes):
        tmp = box_h(out, r)
        out = box_v(tmp, r)
    return out


def big_blur(src, factor=DOWN_FACTOR):
    h, w = src.shape
    sw, sh = (w + factor - 1) // factor, (h + factor - 1) // factor
    small = np.zeros((sh, sw), np.float32)
    for sy in range(sh):
        y0, y1 = sy * factor, min(sy * factor + factor, h)
        for sx in range(sw):
            x0, x1 = sx * factor, min(sx * factor + factor, w)
            small[sy, sx] = src[y0:y1, x0:x1].mean()
    blurred = gauss(small, 6)          # 降采样图 σ≈6.25
    # 双线性上采样（与 Java 端一致）
    fy = np.clip((np.arange(h) + 0.5) / factor - 0.5, 0, sh - 1)
    fx = np.clip((np.arange(w) + 0.5) / factor - 0.5, 0, sw - 1)
    y0 = np.floor(fy).astype(int); y1 = np.minimum(y0 + 1, sh - 1); ty = (fy - y0)[:, None]
    x0 = np.floor(fx).astype(int); x1 = np.minimum(x0 + 1, sw - 1); tx = (fx - x0)[None, :]
    a = blurred[np.ix_(y0, x0)]; b = blurred[np.ix_(y0, x1)]
    c = blurred[np.ix_(y1, x0)]; d = blurred[np.ix_(y1, x1)]
    top = a + (b - a) * tx
    bot = c + (d - c) * tx
    return (top + (bot - top) * ty).astype(np.float32)


def sobel_acc(src, acc):
    gx = (-src[:-2, :-2] + src[:-2, 2:] - 2 * src[1:-1, :-2] + 2 * src[1:-1, 2:]
          - src[2:, :-2] + src[2:, 2:])
    gy = (-src[:-2, :-2] - 2 * src[:-2, 1:-1] - src[:-2, 2:]
          + src[2:, :-2] + 2 * src[2:, 1:-1] + src[2:, 2:])
    mag = np.sqrt(gx * gx + gy * gy)
    np.maximum(acc[1:-1, 1:-1], mag, out=acc[1:-1, 1:-1])


def percentile(a, q):
    mx = float(a.max())
    if mx <= 0:
        return 0.0
    hist, _ = np.histogram(a, bins=2048, range=(0, mx))
    target = a.size * q
    cum = np.cumsum(hist)
    b = int(np.searchsorted(cum, target))
    return (b + 0.5) / 2048.0 * mx


def lerp3(d, v0, v50, v100):
    """分段线性：d=0→v0，d=0.5→v50，d=1→v100。"""
    return v0 + (v50 - v0) * (d / .5) if d <= .5 else v50 + (v100 - v50) * ((d - .5) / .5)


def process(bgr, preset=1, strength=50, detail=50, clean_background=True,
            threshold=None, edge_weight=None, min_area=None, mask_power=1.0,
            min_span=0, edge_clean=True, edge_area=None, edge_span=None,
            smooth_radius=None):
    """与 Java 端 StencilProcessor 保持一致。
    preset 0/1/2 = 细线/标准/浓线；detail 0..100 = 细节保留程度；其余为调参覆盖项。
    """
    tuning = {0: (70., .26, 60, 1), 1: (62., .32, 70, 1), 2: (48., .45, 90, 3)}[preset]
    t, ew, ma, dilate = tuning
    detail = int(np.clip(detail, 0, 100))
    d = detail / 100.0
    if threshold is None:
        threshold = float(np.clip(t - (strength - 50) * 0.28 + (0.5 - d) * 16, 12, 105))
    edge_weight = ew if edge_weight is None else edge_weight
    min_area = max(1, round(ma * lerp3(d, 3.0, 1.0, .12))) if min_area is None else min_area
    edge_area = round(1500 * lerp3(d, 2.2, 1.0, .25)) if edge_area is None else edge_area
    edge_span = round(220 * lerp3(d, 1.5, 1.0, .5)) if edge_span is None else edge_span
    smooth_radius = (2 if detail < 40 else 1) if smooth_radius is None else smooth_radius

    h0, w0 = bgr.shape[:2]
    ratio = min(1.0, MAX_SIDE / max(w0, h0))
    w, h = max(1, round(w0 * ratio)), max(1, round(h0 * ratio))
    img = cv2.resize(bgr, (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)

    gray = .299 * img[:, :, 2] + .587 * img[:, :, 1] + .114 * img[:, :, 0]
    sm = gauss(gray.astype(np.float32), smooth_radius)   # 低细节档多抹掉高频毛刺

    white = None
    if clean_background:
        white = np.clip((big_blur(sm) - 25.) / 60., 0, 1).astype(np.float32)
        g2 = (sm * white + 255. * (1 - white)).astype(np.float32)
    else:
        g2 = sm.copy()

    bg2 = gauss(g2, 9)                              # σ≈10
    gate = np.clip((190. - g2) / 130., 0, 1)
    if mask_power > 0 and white is not None:
        gate = gate * (white ** mask_power)
    response = np.maximum(bg2 - g2, 0) * gate
    p0 = percentile(response, .995)

    if edge_weight > 0 and p0 > 0:
        edge = np.zeros((h, w), np.float32)
        for c in range(3):
            chan = gauss(img[:, :, c], 1)
            if white is not None:
                chan = (chan * white + 255. * (1 - white)).astype(np.float32)
            sobel_acc(chan, edge)
        sobel_acc(g2, edge)
        mp = percentile(edge, .99)
        if mp > 0:
            response = np.maximum(response, np.clip(edge / mp, 0, 1) * p0 * edge_weight * gate)

    p = percentile(response, .995)
    if p <= 0:
        ink = np.zeros((h, w), np.uint8)
    else:
        ink = ((response * (255. / p)) > threshold).astype(np.uint8)

    if dilate > 1:
        ink = cv2.dilate(ink, np.ones((dilate, dilate), np.uint8))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(ink, 8)
    keep = np.zeros_like(ink)
    margin = int(min(w, h) * 0.055)
    for i in range(1, n):
        area = stats[i, cv2.CC_STAT_AREA]
        span = max(stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT])
        if area < min_area or (span < min_span and area < min_area * 12):
            continue
        if edge_clean:
            x, y, bw, bh = stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_TOP], \
                           stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
            near_edge = (x <= margin or y <= margin or
                         x + bw >= w - margin or y + bh >= h - margin)
            if near_edge and area < edge_area and span < edge_span:
                continue
        keep[lab == i] = 1
    out = np.where(keep != 0, 0, 255).astype(np.uint8)
    return cv2.cvtColor(out, cv2.COLOR_GRAY2BGR)


if __name__ == '__main__':
    src = sys.argv[1] if len(sys.argv) > 1 else 'samples/01-input.jpg'
    dst = sys.argv[2] if len(sys.argv) > 2 else 'samples/03-result.png'
    bgr = cv2.imread(src)
    if bgr is None:
        raise SystemExit('cannot read ' + src)
    detail = int(sys.argv[3]) if len(sys.argv) > 3 else 50
    res = process(bgr, detail=detail)
    cv2.imwrite(dst, res)
    print('saved', dst, res.shape, 'detail', detail,
          'ink ratio', round(float((res[:, :, 0] == 0).mean()), 4))
