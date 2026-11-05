import sys, numpy as np
sys.path.insert(0, '.')
from sim import frame_from, tpl_from, search_one, classify, SCALES
A = '/root/.codebuddy/artifact/yys/app/assets/'
MOB = ['tpl_mob_a.png','tpl_mob_b.png','tpl_mob_c.png','tpl_mob_d.png']
BOSS = ['tpl_boss_a.png','tpl_boss_b.png']

def load(names, W):
    out = []
    for s in SCALES:
        for n in names:
            out.append((n, tpl_from(A+n, W, s)[0]))
    return out

def any_mode(path, W=2408, th=0.90, topN=2):
    fr, rgb = frame_from(path, W)
    tpls = load(MOB, W) + load(BOSS, W)
    work = fr.copy()
    cands = []
    for _ in range(topN):
        best = None
        for name, t in tpls:
            sc = search_one(work, t)
            i = int(np.argmax(sc)); v = float(sc.flat[i])
            y, x = divmod(i, sc.shape[1])
            if best is None or v > best[0]:
                best = (v, x, y, t.shape[0], name)
        v, x, y, n, name = best
        if v < th:
            break
        typ, b, rd = classify(rgb, x, y, n)
        cands.append((v, x, y, n, name, typ, b, rd))
        cx, cy = x + n//2, y + n//2
        r = n//2 + 6
        work[max(0,cy-r):cy+r, max(0,cx-r):cx+r] = 0
    if not cands:
        return None
    boss = [c for c in cands if c[5] == 1]
    pick = boss[0] if boss else cands[0]
    v, x, y, n, name, typ, b, rd = pick
    return dict(score=v, x=(x+n//2)*4, y=(y+n//2)*4, typ=typ, color=(b, rd), tpl=name)

for img in ['s1','s2','s3','s4','s5']:
    p = 'shots/v12/%s.jpg' % img if img != 's5' else 'shots/v12/s5.png'
    r = any_mode(p)
    if r is None:
        print('%s  无命中' % img)
    else:
        t = {0:'小怪',1:'首领',-1:'判不出'}[r['typ']]
        print('%-3s 选中 %-4s %3d%%  位置(%d,%d)  蓝%.2f/红%.2f  模板%s'
              % (img, t, round(r['score']*100), r['x'], r['y'], r['color'][0], r['color'][1], r['tpl']))
