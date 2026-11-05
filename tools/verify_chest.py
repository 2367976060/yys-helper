import sys, numpy as np
sys.path.insert(0, '.')
from sim import frame_from, tpl_from, search_one, SCALES
A = '/root/.codebuddy/artifact/yys/app/assets/'
CHEST = 'tpl_chest_a.png'

def best_chest(path, W=2408):
    fr, rgb = frame_from(path, W)
    best = (-2, None, None, None)
    for sc in SCALES:
        t, n = tpl_from(A + CHEST, W, sc)
        s = search_one(fr, t)
        i = int(np.argmax(s)); v = float(s.flat[i])
        y, x = divmod(i, s.shape[1])
        if v > best[0]:
            best = (v, sc, x, y, n)
    v, sc, x, y, n = best
    cx = (x + n // 2) * 4
    cy = (y + n // 2) * 4
    return v, sc, cx, cy

cases = [
    ('shots/v17/c1.jpg', '战后宝箱界面(1920)'),
    ('shots/v12/s5.png', '其他手机首领画面'),
    ('shots/v12/s1.jpg', '战斗/地图'),
    ('shots/v12/s2.jpg', '战斗/地图'),
    ('shots/v12/s3.jpg', '战斗/地图'),
    ('shots/v12/s4.jpg', '战斗/地图'),
    ('shots/v16/b2.jpg', '探索地图'),
]
for p, tag in cases:
    v, sc, cx, cy = best_chest(p)
    print('%-22s %-16s 最高 %.3f (scale %.2f) @ (%d,%d)' % (p.split('/')[-1], tag, v, sc, cx, cy))
