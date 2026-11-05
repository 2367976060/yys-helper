import numpy as np
from PIL import Image

FILES = [
    ('庭院-有控制台', 'shots/yard1.jpg'),
    ('庭院-无控制台', 'shots/yard2.jpg'),
    ('探索地图-小怪', '/workspace/阴阳师助手/识别效果-高光小怪.png'),
    ('探索地图-首领', '/workspace/阴阳师助手/识别效果-跳过小怪打首领.png'),
    ('章节选择页', '/workspace/阴阳师助手/识别效果-章节列表进图.png'),
    ('章节详情页', '/workspace/阴阳师助手/识别效果-章节探索按钮.png'),
    ('喂食面板', '/workspace/阴阳师助手/识别效果-喂食面板不误点.png'),
    ('战后宝箱', '/workspace/阴阳师助手/识别效果-战后宝箱.png'),
    ('卡住-宝箱屏', 'shots/stuck_panel.jpg'),
    ('确认退出框', 'shots/quit_dialog.jpg'),
]


def yard_screen(path):
    """复刻 Engine.yardScreen()：底部金色玩法按钮占比。"""
    im = np.asarray(Image.open(path).convert('RGB')).astype(int)
    H, W, _ = im.shape
    x0, x1 = int(0.05 * W), int(0.66 * W)
    y0 = int(0.86 * H)
    sub = im[y0:H, x0:x1]
    r, g, b = sub[:, :, 0], sub[:, :, 1], sub[:, :, 2]
    gold = (r > 120) & (g > 90) & (b < 90) & ((r - b) > 55)
    return gold.mean() * 100


print('%-16s %8s  %s' % ('截图', '金色占比', '判定'))
print('-' * 44)
for name, p in FILES:
    v = yard_screen(p)
    print('%-16s %7.1f%%  %s' % (name, v, '庭院 → 点章节' if v >= 8 else '不是庭院'))

print()
print('庭院三行落点（1920x861 基准 / 比例）：')
for i, (x, y) in enumerate([(0.828, 0.539), (0.828, 0.425), (0.828, 0.311)]):
    print('  第%d行  (%.0f, %.0f)  (%.3f, %.3f)' % (3 - i, x * 1920, y * 861, x, y))
