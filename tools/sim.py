import sys, math
import numpy as np
from PIL import Image

SCALE = 4
MASK_R = 0.42
REF_W = 1920
CROP = 104

def frame_from(path, target_w=2408):
    im = Image.open(path).convert('RGB')
    w0, h0 = im.size
    h = round(h0 * target_w / w0)
    im = im.resize((target_w, h), Image.LANCZOS)
    a = np.asarray(im).astype(np.int32)
    g = (a[:, :, 0] * 77 + a[:, :, 1] * 151 + a[:, :, 2] * 28) >> 8
    rgb = a
    W = target_w // SCALE
    H = h // SCALE
    gg = g[::SCALE, ::SCALE][:H, :W]
    rr = rgb[::SCALE, ::SCALE][:H, :W]
    return gg, rr

SCALES=[0.88,1.00,1.14]
def tpl_from(path, target_w=2408, sc=1.0):
    im = Image.open(path).convert('RGB')
    target = max(40, round(CROP * target_w / REF_W * sc))
    n = (target + SCALE - 1) // SCALE
    im = im.resize((target, target), Image.LANCZOS)
    a = np.asarray(im).astype(np.int32)
    g = (a[:, :, 0] * 77 + a[:, :, 1] * 151 + a[:, :, 2] * 28) >> 8
    idx = np.arange(n) * SCALE
    idx = np.minimum(idx, target - 1)
    gt = g[np.ix_(idx, idx)]
    return gt, n

def mask_idx(n):
    c = (n - 1) / 2.0
    r = MASK_R * n / 2.0
    yy, xx = np.mgrid[0:n, 0:n]
    m = ((xx - c) ** 2 + (yy - c) ** 2) <= r * r
    return m

def search_one(frame, tpl):
    n = tpl.shape[0]
    m = mask_idx(n)
    H, W = frame.shape
    ys, xs = H - n + 1, W - n + 1
    f = frame.astype(np.float64)
    s = np.zeros((ys, xs)); ss = np.zeros((ys, xs)); cr = np.zeros((ys, xs))
    idxs = np.argwhere(m)
    cnt = len(idxs)
    tv = 0.0
    tm = float(tpl[m].mean())
    for dy, dx in idxs:
        sub = f[dy:dy + ys, dx:dx + xs]
        s += sub
        ss += sub * sub
        cr += sub * tpl[dy, dx]
    mean = s / cnt
    var = ss - s * mean
    tv = float(((tpl[m] - tm) ** 2).sum())
    with np.errstate(divide='ignore', invalid='ignore'):
        sc = (cr - cnt * mean * tm) / np.sqrt(var * tv)
    sc[var <= 1e-6] = -2
    sc = np.nan_to_num(sc, nan=-2)
    return sc

def search_multi(frame, tpls, topN, minScore):
    work = frame.copy()
    out = []
    for _ in range(topN):
        best = None
        for t in tpls:
            sc = search_one(work, t)
            i = int(np.argmax(sc))
            v = float(sc.flat[i])
            y, x = divmod(i, sc.shape[1])
            if best is None or v > best[0]:
                best = (v, x, y, t.shape[0])
        v, x, y, n = best
        if v < minScore:
            break
        out.append((v, x, y, n))
        cx, cy = x + n // 2, y + n // 2
        r = n // 2 + 2
        work[max(0, cy - r):cy + r, max(0, cx - r):cx + r] = 0
    return out

def classify(rgb, x, y, n):
    c = (n - 1) / 2.0
    r = 0.32 * n
    yy, xx = np.mgrid[0:n, 0:n]
    m = ((xx - c) ** 2 + (yy - c) ** 2) <= r * r
    H, W, _ = rgb.shape
    sub = rgb[y:y + n, x:x + n]
    if sub.shape[0] != n or sub.shape[1] != n:
        return -1, 0, 0
    R = sub[:, :, 0].astype(np.int32); G = sub[:, :, 1].astype(np.int32); B = sub[:, :, 2].astype(np.int32)
    blue = ((B > R + 25) & (B > 90) & m).sum()
    red = ((R > B + 55) & (R > 130) & m).sum()
    cnt = m.sum()
    b = blue / cnt; rd = red / cnt
    t = -1
    if b - rd > 0.04: t = 0
    elif rd - b > 0.04: t = 1
    return t, round(b, 3), round(rd, 3)

if __name__ == '__main__':
    W = int(sys.argv[2]) if len(sys.argv) > 2 else 2408
    th = float(sys.argv[3]) if len(sys.argv) > 3 else 0.90
    frame, rgb = frame_from(sys.argv[1], W)
    print('frame', frame.shape)
    A = '/root/.codebuddy/artifact/yys/app/assets/'
    for label, files in [('MOB', ['tpl_mob_a.png', 'tpl_mob_b.png', 'tpl_mob_c.png', 'tpl_mob_d.png']),
                         ('BOSS', ['tpl_boss_a.png', 'tpl_boss_b.png'])]:
        tpls = [tpl_from(A + f, W, s)[0] for s in SCALES for f in files]
        print('   templates:', len(tpls), 'sizes', sorted(set(t.shape[0] for t in tpls)))
        hits = search_multi(frame, tpls, 5, th)
        print('===', label, 'th', th)
        for (v, x, y, n) in hits:
            t, b, rd = classify(rgb, x, y, n)
            print('  score=%.3f at (%d,%d) size=%d  real=(%d,%d)  classify=%s blue=%.3f red=%.3f'
                  % (v, x, y, n, (x + n // 2) * SCALE, (y + n // 2) * SCALE,
                     {-1: '未知', 0: '小怪', 1: '首领'}[t], b, rd))
