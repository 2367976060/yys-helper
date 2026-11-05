import sys, numpy as np
sys.path.insert(0, '.')
from PIL import Image
from sim import frame_from, SCALE

A = '/root/.codebuddy/artifact/yys/app/assets/'
SCALES = [0.88, 1.00, 1.14]

def tpl_at(name, crop, W=2408, sc=1.0):
    im = Image.open(A + name).convert('RGB')
    target = max(40, round(crop * W / 1920 * sc))
    n = (target + SCALE - 1) // SCALE
    im = im.resize((target, target), Image.LANCZOS)
    a = np.asarray(im).astype(np.int32)
    g = (a[:, :, 0]*77 + a[:, :, 1]*151 + a[:, :, 2]*28) >> 8
    idx = np.minimum(np.arange(n)*SCALE, target-1)
    return g[np.ix_(idx, idx)]

def search_rect(frame, tpl, fx0, fy0, fx1, fy1):
    n = tpl.shape[0]
    H, W = frame.shape
    x0 = max(0, min(W-1, round(fx0*W))); y0 = max(0, min(H-1, round(fy0*H)))
    x1 = max(x0+1, min(W, round(fx1*W))); y1 = max(y0+1, min(H, round(fy1*H)))
    sub = frame[y0:y1, x0:x1]
    ys, xs = sub.shape[0]-n+1, sub.shape[1]-n+1
    if ys <= 0 or xs <= 0:
        return -2, None
    f = sub.astype(np.float64)
    s = np.zeros((ys, xs)); ss = np.zeros((ys, xs)); cr = np.zeros((ys, xs))
    for dy in range(n):
        for dx in range(n):
            v = f[dy:dy+ys, dx:dx+xs]
            s += v; ss += v*v; cr += v*tpl[dy, dx]
    cnt = n*n
    mean = s/cnt
    var = ss - s*mean
    tm = float(tpl.mean()); tv = float(((tpl-tm)**2).sum())
    with np.errstate(divide='ignore', invalid='ignore'):
        sc = (cr - cnt*mean*tm)/np.sqrt(var*tv)
    sc[var <= 1e-6] = -2
    sc = np.nan_to_num(sc, nan=-2)
    i = int(np.argmax(sc)); yy, xx = divmod(i, sc.shape[1])
    return float(sc.flat[i]), (x0+xx+n//2, y0+yy+n//2, n)

def best(name, crop, path, rect):
    fr, _ = frame_from(path, 2408)
    b = (-2, None, None)
    for sc in SCALES:
        t = tpl_at(name, crop, 2408, sc)
        v, pos = search_rect(fr, t, *rect)
        if v > b[0]:
            b = (v, sc, pos)
    return b

CH_RECT = (0.58, 0.72, 1.0, 1.0)
CB_RECT = (0.18, 0.38, 0.78, 0.88)

cases = [
    ('shots/v18/d1.jpg', '章节界面'),
    ('shots/v17/c1.jpg', '宝箱界面'),
    ('shots/v16/b2.jpg', '探索地图'),
    ('shots/v12/s1.jpg', '战斗1'),
    ('shots/v12/s3.jpg', '战斗3'),
    ('shots/v12/s5.png', '首领特写'),
]
print('=== 「探索」木牌（右下区域） ===')
res = []
for p, tag in cases:
    v, sc, pos = best('tpl_chapter_a.png', 130, p, CH_RECT)
    res.append(v)
    print('%-10s %-8s %.3f  scale=%.2f  @%s' % (p.split('/')[-1], tag, v, sc, pos))
print('命中 %.3f / 最高误检 %.3f / 间隔 %.3f' % (res[0], max(res[1:]), res[0]-max(res[1:])))

print()
print('=== 战后宝箱（中下区域） ===')
res = []
for p, tag in cases:
    v, sc, pos = best('tpl_chest_a.png', 104, p, CB_RECT)
    res.append(v)
    print('%-10s %-8s %.3f  scale=%.2f  @%s' % (p.split('/')[-1], tag, v, sc, pos))
# 宝箱命中是 c1（index 1）
print('命中 %.3f / 最高误检 %.3f / 间隔 %.3f' % (res[1], max(res[0], max(res[2:])), res[1]-max(res[0], max(res[2:]))))
