import sys, numpy as np
sys.path.insert(0, '.')
from sim import frame_from, tpl_from, SCALES
A = '/root/.codebuddy/artifact/yys/app/assets/'

def search_full(frame, tpl):
    """全模板 NCC（不用圆内掩模）"""
    n = tpl.shape[0]
    H, W = frame.shape
    ys, xs = H - n + 1, W - n + 1
    f = frame.astype(np.float64)
    s = np.zeros((ys, xs)); ss = np.zeros((ys, xs)); cr = np.zeros((ys, xs))
    for dy in range(n):
        for dx in range(n):
            sub = f[dy:dy + ys, dx:dx + xs]
            s += sub
            ss += sub * sub
            cr += sub * tpl[dy, dx]
    cnt = n * n
    mean = s / cnt
    var = ss - s * mean
    tm = float(tpl.mean())
    tv = float(((tpl - tm) ** 2).sum())
    with np.errstate(divide='ignore', invalid='ignore'):
        sc = (cr - cnt * mean * tm) / np.sqrt(var * tv)
    sc[var <= 1e-6] = -2
    return np.nan_to_num(sc, nan=-2)

def best_chest(path, W=2408):
    fr, _ = frame_from(path, W)
    best = (-2, None, None, None, None)
    for sc in SCALES:
        t, n = tpl_from(A + 'tpl_chest_a.png', W, sc)
        s = search_full(fr, t)
        i = int(np.argmax(s)); v = float(s.flat[i])
        y, x = divmod(i, s.shape[1])
        if v > best[0]:
            best = (v, sc, x, y, n)
    v, sc, x, y, n = best
    return v, sc, (x + n // 2) * 4, (y + n // 2) * 4

cases = [
    ('shots/v17/c1.jpg', '战后宝箱'),
    ('shots/v12/s5.png', '首领画面'),
    ('shots/v12/s1.jpg', '战斗1'),
    ('shots/v12/s2.jpg', '战斗2'),
    ('shots/v12/s3.jpg', '战斗3'),
    ('shots/v12/s4.jpg', '战斗4'),
    ('shots/v16/b2.jpg', '探索地图'),
]
res = []
for p, tag in cases:
    v, sc, cx, cy = best_chest(p)
    res.append(v)
    print('%-10s %-8s 全模板 %.3f (scale %.2f) @ (%d,%d)' % (p.split('/')[-1], tag, v, sc, cx, cy))
print('\n命中 vs 误检间隔: %.3f' % (res[0] - max(res[1:])))
