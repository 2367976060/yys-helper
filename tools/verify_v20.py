"""v1.20 流程复算：章节(0.70) → 宝箱(0.80) → 怪物(按阈值)。
把 Engine 的新判定顺序在真机截图上跑一遍，确认每张图都会走到预期动作。"""
import numpy as np, sim
from PIL import Image, ImageDraw

A = '/root/.codebuddy/artifact/yys/app/assets/'
CHEST_TH = 0.80
CHAP_TH = 0.70
MOB_TH = 0.90

mobs = [sim.tpl_from(A + f, 2408, s)[0] for s in sim.SCALES
        for f in ['tpl_mob_a.png', 'tpl_mob_b.png', 'tpl_mob_c.png', 'tpl_mob_d.png']]
bosses = [sim.tpl_from(A + f, 2408, s)[0] for s in sim.SCALES
          for f in ['tpl_boss_a.png', 'tpl_boss_b.png']]
chests = [sim.tpl_from(A + 'tpl_chest_a.png', 2408, s)[0] for s in sim.SCALES]
chaps = [sim.tpl_from(A + 'tpl_chapter_a.png', 2408, s)[0] for s in sim.SCALES]


def find_chap(fr):
    H, W = fr.shape
    x0, y0 = int(0.58 * W), int(0.72 * H)
    sub = fr[y0:H, x0:W]
    best = (-2, 0, 0, 0)
    for t in chaps:
        sc = sim.search_one(sub, t)
        i = int(np.argmax(sc)); yy, xx = divmod(i, sc.shape[1]); v = float(sc.flat[i])
        if v > best[0]:
            best = (v, xx + x0, yy + y0, t.shape[0])
    return best if best[0] >= CHAP_TH else None, best


def find_chest(fr):
    hits = sim.search_multi(fr, chests, 1, -2)
    return hits[0] if hits else None


def find_mon(fr):
    hits = sim.search_multi(fr, mobs + bosses, 2, MOB_TH)
    return hits


files = [
    ('高光小怪', '/workspace/阴阳师助手/识别效果-高光小怪.png'),
    ('跳过小怪打首领', '/workspace/阴阳师助手/识别效果-跳过小怪打首领.png'),
    ('章节列表进图', '/workspace/阴阳师助手/识别效果-章节列表进图.png'),
    ('章节探索按钮', '/workspace/阴阳师助手/识别效果-章节探索按钮.png'),
    ('喂食面板不误点', '/workspace/阴阳师助手/识别效果-喂食面板不误点.png'),
    ('战后宝箱', '/workspace/阴阳师助手/识别效果-战后宝箱.png'),
    ('本次卡住的图', '/root/.codebuddy/artifact/yys/shots/stuck_panel.jpg'),
]

print('%-14s %-22s %-22s %s' % ('截图', '章节木牌(>=0.70)', '宝箱(>=0.80)', '怪物(>=0.90)'))
for name, path in files:
    fr, rgb = sim.frame_from(path)
    chap, chapBest = find_chap(fr)
    chest = find_chest(fr)
    mon = find_mon(fr)
    chapTxt = ('命中 %.3f' % chapBest[0]) if chap else '无(最高%.3f)' % chapBest[0]
    chestTxt = ('命中 %.3f @(%d,%d)' % (chest[0], (chest[1] + chest[3] // 2) * 4,
                                        (chest[2] + chest[3] // 2) * 4)) if chest and chest[0] >= CHEST_TH \
        else '无(最高%.3f)' % (chest[0] if chest else 0)
    monTxt = ('%d 只 最高%.3f' % (len(mon), mon[0][0])) if mon else '无'
    print('%-14s %-22s %-22s %s' % (name, chapTxt, chestTxt, monTxt))
