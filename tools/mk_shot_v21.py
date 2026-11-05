from PIL import Image, ImageDraw, ImageFont

SRC = '/root/.codebuddy/artifact/yys/shots/quit_dialog.jpg'
OUT = '/workspace/阴阳师助手/识别效果-退出框自救-v1.21.png'
F = '/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc'

im = Image.open(SRC).convert('RGB')
W, H = im.size
HEAD = 78
PAD = 96
canvas = Image.new('RGB', (W, H + HEAD + PAD), (24, 20, 38))
canvas.paste(im, (0, HEAD))
d = ImageDraw.Draw(canvas)

f_head = ImageFont.truetype(F, 32)
f_small = ImageFont.truetype(F, 24)

d.text((28, 22), 'v1.21  这个白框就是「确认退出」，现在会自动点「取消」继续跑', font=f_head, fill=(255, 235, 180))

# 白框范围（实测 0.447~0.744 x, 0.230~0.768 y）
box = [0.447 * W, 0.230 * H + HEAD, 0.744 * W, 0.768 * H + HEAD]
for w in range(4):
    d.rectangle([box[0] - w, box[1] - w, box[2] + w, box[3] + w], outline=(120, 255, 190))

# 「取消」落点
cx, cy = 0.586 * W, 0.677 * H + HEAD
d.ellipse([cx - 30, cy - 30, cx + 30, cy + 30], outline=(255, 110, 110), width=4)
d.line([cx, cy - 30, cx, cy - 96], fill=(255, 110, 110), width=3)
lab = '点这里「取消」'
tw = d.textlength(lab, font=f_small)
d.rounded_rectangle([cx - tw / 2 - 12, cy - 96 - 46, cx + tw / 2 + 12, cy - 96], radius=10, fill=(48, 20, 24))
d.text((cx - tw / 2, cy - 96 - 40), lab, font=f_small, fill=(255, 150, 150))

y0 = H + HEAD + 14
steps = ['① 返回键已彻底删掉', '② 白框自动点「取消」', '③ 宝箱 0.68 以上也点', '④ 点完怪必点出战']
x = 24
for i, s in enumerate(steps):
    tw = d.textlength(s, font=f_small)
    col = (110, 230, 255)
    d.rounded_rectangle([x, y0, x + tw + 34, y0 + 52], radius=12,
                        outline=col, width=2, fill=(40, 34, 58))
    d.text((x + 17, y0 + 10), s, font=f_small, fill=col)
    x += tw + 34 + 16

canvas.save(OUT)
print(OUT, canvas.size)
