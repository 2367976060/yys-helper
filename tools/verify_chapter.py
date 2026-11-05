import sys, numpy as np
sys.path.insert(0, '.')
from PIL import Image
from sim import frame_from, SCALE, SCALES

def tpl_custom(path, crop, W, sc=1.0):
    im = Image.open(path).convert('RGB')
    target = max(40, round(crop * W / 1920 * sc))
    n = (target + SCALE - 1) // SCALE
    im = im.resize((target, target), Image.LANCZOS)
    a = np.asarray(im).astype(np.int32)
    g = (a[:, :, 0] * 77 + a[:, :, 1] * 151 + a[:, :, 2] * 28) >> 8
    idx = np.minimum(np.arange(n) * SCALE, target - 1)
    return g[np.ix_(idx, idx)], n

def search_full(frame, tpl):
    n = tpl.shape[0]
    H, W = frame.shape
    ys, xs = H - n + 1, W - n + 1
    f = frame.astype(np.float64)
    s = np.zeros((ys, xs)); ss = np.zeros((ys, xs)); cr = np.zeros((ys, xs))
    for dy in range(n):
        for dx in range(n):
            sub = f[dy:dy + ys, dx:dx + xs]
            s += sub; ss += sub * sub; cr += sub * tpl[dy, dx]
    cnt = n * n
    mean = s / cnt
    var = ss - s * mean
    tm = float(tpl.mean()); tv = float(((tpl - tm) ** 2).sum())
    with np.errstate(divide='ignore', invalid='ignore'):
        sc = (cr - cnt * mean * tm) / np.sqrt(var * tv)
    sc[var <= 1e-6] = -2
    return np.nan_to_num(sc, nan=-2)

def best(path, tplname, crop, W=2408):
    fr, _ = frame_from(path, W)
    best = (-2, None, None, None, None)
    for sc in SCALES:
        t, n = tpl_custom('/root/.codebuddy/artifact/yys/app/assets/' + tplname, crop, W, sc)
        s = search_full(fr, t)
        i = int(np.argmax(s)); v = float(s.flat[i])
        y, x = divmod(i, s.shape[1])
        if v > best[0]:
            best = (v, sc, x, y, n)
    v, sc, x, y, n = best
    return v, sc, (x + n // 2) * 4, (y + n // 2) * 4

cases = [
    ('shots/v18/d1.jpg', '章节界面(要打)'),
    ('shots/v17/c1.jpg', '宝箱界面'),
    ('shots/v16/b2.jpg', '探索地图'),
    ('shots/v12/s1.jpg', '战斗1'),
    ('shots/v12/s3.jpg', '战斗3'),
    ('shots/v12/s5.png', '首领特写'),
]
res = []
for p, tag in cases:
    v, sc, cx, cy = best(p, 'tpl_chapter_a.png', 130)
    res.append((tag, v))
    print('%-14s %-10s %.3f  scale=%.2f  @ (%d,%d)' % (p.split('/')[-1], tag, v, sc, cx, cy))
hit = res[0][1]
print('\n命中 %.3f  最高误检 %.3f  间隔 %.3f' % (hit, max(r[1] for r in res[1:]), hit - max(r[1] for r in res[1:])))
