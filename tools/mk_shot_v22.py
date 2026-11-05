from PIL import Image, ImageDraw, ImageFont

SRC = '/root/.codebuddy/artifact/yys/shots/yard2.jpg'
OUT = '/workspace/阴阳师助手/识别效果-庭院直接进副本-v1.22.png'
F = '/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc'

im = Image.open(SRC).convert('RGB')
W, H = im.size
HEAD = 78
PAD = 128
canvas = Image.new('RGB', (W, H + HEAD + PAD), (24, 20, 38))
canvas.paste(im, (0, HEAD))
d = ImageDraw.Draw(canvas)

f_head = ImageFont.truetype(F, 32)
f_small = ImageFont.truetype(F, 24)
f_lbl = ImageFont.truetype(F, 22)

d.text((28, 22), 'v1.22  认出庭院（底部金色玩法按钮）→ 直接点开章节 → 点「探索」进副本',
       font=f_head, fill=(255, 235, 180))

# ① 底部金色玩法按钮带：识别区域
bx0, by0 = 0.05 * W, 0.86 * H + HEAD
bx1, by1 = 0.66 * W, H + HEAD
for w in range(4):
    d.rectangle([bx0 - w, by0 - w, bx1 + w, by1 + w], outline=(110, 230, 255))
tw = d.textlength('识别：底部 7 个金色玩法按钮  23.4% ≥ 8%', font=f_lbl)
d.rounded_rectangle([bx0, by0 - 52, bx0 + tw + 24, by0 - 8], radius=10, fill=(16, 40, 52))
d.text((bx0 + 12, by0 - 48), '识别：底部 7 个金色玩法按钮  23.4% ≥ 8%', font=f_lbl, fill=(110, 230, 255))

# ② 右侧章节面板
px0, py0 = 1535, 160 + HEAD
px1, py1 = 1832, 620 + HEAD
for w in range(3):
    d.rectangle([px0 - w, py0 - w, px1 + w, py1 + w], outline=(255, 200, 120))
tw = d.textlength('章节目录：三行都能点', font=f_lbl)
d.rounded_rectangle([px0, py0 - 44, px0 + tw + 22, py0 - 6], radius=9, fill=(56, 38, 16))
d.text((px0 + 11, py0 - 40), '章节目录：三行都能点', font=f_lbl, fill=(255, 200, 120))

# ③ 三行落点（从下往上试）
rows = [(0.833, 0.652, '③ 先试这行', (120, 255, 190)),
        (0.833, 0.506, '② 再上一行', (200, 220, 255)),
        (0.833, 0.359, '① 最后顶行', (200, 220, 255))]
for i, (fx, fy, lab, col) in enumerate(rows):
    cx, cy = fx * W, fy * H + HEAD
    r = 30 if i == 0 else 22
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=4 if i == 0 else 2)
    d.line([cx - r - 6, cy, cx - r - 26, cy], fill=col, width=3)
    tw = d.textlength(lab, font=f_lbl)
    d.rounded_rectangle([cx - r - 26 - tw - 22, cy - 20, cx - r - 26, cy + 20],
                        radius=9, fill=(20, 34, 26) if i == 0 else (26, 26, 40))
    d.text((cx - r - 26 - tw - 11, cy - 15), lab, font=f_lbl, fill=col)

# 底部步骤条
y0 = H + HEAD + 20
steps = ['① 底部按钮判定在庭院', '② 从下往上点一行章节', '③ 看到「探索」才点下去', '④ 点空自动换上一行']
x = 24
for s in steps:
    tw = d.textlength(s, font=f_lbl)
    col = (255, 214, 140)
    d.rounded_rectangle([x, y0, x + tw + 34, y0 + 50], radius=12,
                        outline=col, width=2, fill=(40, 34, 58))
    d.text((x + 17, y0 + 10), s, font=f_lbl, fill=col)
    x += tw + 34 + 16

canvas.save(OUT)
print(OUT, canvas.size)
