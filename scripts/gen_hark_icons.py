# Generate Hark launcher icons from hark.jpg for all densities.
# - ic_launcher.png: square icon as-is (center-cropped to square, resized)
# - ic_launcher_foreground*.png: image shrunk into the adaptive-icon safe
#   zone (66/108 of canvas) on a transparent canvas, so the existing
#   adaptive-icon XMLs (mipmap-anydpi-v26) keep working unchanged.
from PIL import Image
import os

SRC = r"C:\Users\Administrator\Desktop\OpenMinis-main\hark.jpg"
RES = r"C:\Users\Administrator\Desktop\OpenMinis-main\src\android\app\src\main\res"

DENSITIES = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}

src = Image.open(SRC).convert("RGBA")
w, h = src.size
side = min(w, h)
left, top = (w - side) // 2, (h - side) // 2
square = src.crop((left, top, left + side, top + side))

def save(img, rel):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print("wrote", rel, img.size)

for dpi, px in DENSITIES.items():
    # Plain launcher icon (used pre-26 fallback + legacy surfaces)
    save(square.resize((px, px), Image.LANCZOS), f"mipmap-{dpi}\\ic_launcher.png")

    # Foreground layers: 108dp canvas, safe zone = center 66dp → image spans
    # 66/108 = 0.6111 of the canvas, centered, transparent padding around.
    fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    inner = int(px * 66 / 108)
    inner_img = square.resize((inner, inner), Image.LANCZOS)
    off = (px - inner) // 2
    fg.paste(inner_img, (off, off))
    save(fg, f"mipmap-{dpi}\\ic_launcher_foreground.png")
    save(fg.copy(), f"mipmap-{dpi}\\ic_launcher_foreground_light.png")
    save(fg.copy(), f"mipmap-{dpi}\\ic_launcher_foreground_dark.png")

# Night variants: currently only launcher + foreground exist there; overwrite
# both so dark-mode launcher matches too.
for dpi, px in DENSITIES.items():
    if dpi == "mdpi":
        continue  # night-mdpi has only launcher+foreground; handle below
    night = os.path.join(RES, f"mipmap-night-{dpi}")
    if os.path.isdir(night):
        save(square.resize((px, px), Image.LANCZOS), f"mipmap-night-{dpi}\\ic_launcher.png")
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        inner = int(px * 66 / 108)
        inner_img = square.resize((inner, inner), Image.LANCZOS)
        off = (px - inner) // 2
        fg.paste(inner_img, (off, off))
        save(fg, f"mipmap-night-{dpi}\\ic_launcher_foreground.png")

# night-mdpi present? handle generically
if os.path.isdir(os.path.join(RES, "mipmap-night-mdpi")):
    save(square.resize((48, 48), Image.LANCZOS), "mipmap-night-mdpi\\ic_launcher.png")
    fg = Image.new("RGBA", (48, 48), (0, 0, 0, 0))
    inner_img = square.resize((29, 29), Image.LANCZOS)
    fg.paste(inner_img, ((48 - 29) // 2, (48 - 29) // 2))
    save(fg, "mipmap-night-mdpi\\ic_launcher_foreground.png")

print("DONE")
