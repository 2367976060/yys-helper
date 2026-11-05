from PIL import Image, ImageDraw, ImageFont

SRC = '/root/.codebuddy/artifact/yys/shots/stuck_panel.jpg'
OUT = '/workspace/阴阳师助手/识别效果-宝箱必点-v1.20.png'
F = '/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc'

im = Image.open(SRC).convert('RGB')
W, H = im.size                     # 1920 x 861
K = 1.2542                         # 1920 -> 2408

HEAD = 78
PAD = 96
canvas = Image.new('RGB', (W, H + HEAD + PAD), (24, 20, 38))
canvas.paste(im, (0, HEAD))
d = ImageDraw.Draw(canvas)

f_head = ImageFont.truetype(F, 34)
f_lbl = ImageFont.truetype(F, 26)
f_small = ImageFont.truetype(F, 24)

d.text((28, 22), 'v1.20  卡住的那一屏：宝箱 0.919 被认出来 → 立刻点开', font=f_head, fill=(255, 235, 180))

# 宝箱命中框（2408 坐标 1444,716 -> 1920 坐标）
cx, cy = 1444 / K, 716 / K
half = 66
box = [cx - half, cy - half + HEAD, cx + half, cy + half + HEAD]
for w in range(4):
    d.rectangle([box[0] - w, box[1] - w, box[2] + w, box[3] + w], outline=(90, 255, 170))
d.line([cx, box[1] - 8, cx, box[1] - 40], fill=(90, 255, 170), width=3)
lab = '宝箱 91.9%  ≥ 80%'
tw = d.textlength(lab, font=f_lbl)
lx, ly = cx - tw / 2 - 14, box[1] - 40 - 44
d.rounded_rectangle([lx, ly, lx + tw + 28, ly + 44], radius=10, fill=(18, 34, 34))
d.text((lx + 14, ly + 7), lab, font=f_lbl, fill=(90, 255, 170))

# 底部流程条
y0 = H + HEAD + 14
steps = ['① 章节页 → 点「探索」', '② 宝箱 ≥80% → 点开领奖', '③ 有怪 → 打怪', '④ 都不认得 → 才脱困翻屏']
x = 24
for i, s in enumerate(steps):
    tw = d.textlength(s, font=f_small)
    col = (110, 230, 255) if i == 1 else (200, 190, 220)
    d.rounded_rectangle([x, y0, x + tw + 34, y0 + 52], radius=12,
                        outline=col, width=2, fill=(40, 34, 58))
    d.text((x + 17, y0 + 10), s, font=f_small, fill=col)
    x += tw + 34 + 16

canvas.save(OUT)
print(OUT, canvas.size)
