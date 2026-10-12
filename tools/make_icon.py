"""Draw the Precipitation app icon (cloud + rain, snow, ice) and save assets/precipitation.ico."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import math

S = 256
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets" / "precipitation.ico"


def main():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # sky tile
    for y in range(S):
        f = y / S
        c = (int(40 + 60 * f), int(90 + 70 * f), int(170 + 50 * f), 255)
        d.line([(0, y), (S, y)], fill=c)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=48, fill=255)
    img.putalpha(mask)

    # cloud: soft shadow then puffs
    cloud = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    cd = ImageDraw.Draw(cloud)
    puffs = [(80, 110, 42), (125, 88, 55), (175, 108, 42), (105, 128, 36), (155, 130, 38)]
    for x, y, r in puffs:
        cd.ellipse([x - r, y - r + 8, x + r, y + r + 8], fill=(70, 80, 100, 160))
    cloud = cloud.filter(ImageFilter.GaussianBlur(6))
    cd = ImageDraw.Draw(cloud)
    for x, y, r in puffs:
        cd.ellipse([x - r, y - r, x + r, y + r], fill=(245, 248, 252, 255))
    cd.rounded_rectangle([50, 110, 205, 160], radius=25, fill=(245, 248, 252, 255))
    cd.rounded_rectangle([58, 146, 197, 160], radius=7, fill=(212, 220, 232, 255))
    img.alpha_composite(cloud)
    d = ImageDraw.Draw(img)

    # raindrops
    for x, y in [(78, 172), (112, 196), (146, 172)]:
        d.line([(x, y), (x - 8, y + 30)], fill=(120, 200, 255, 255), width=7)
    # snowflake
    cx, cy, r = 186, 200, 20
    for k in range(3):
        a = k * math.pi / 3
        dx, dy = r * math.cos(a), r * math.sin(a)
        d.line([(cx - dx, cy - dy), (cx + dx, cy + dy)], fill=(255, 255, 255, 255), width=5)
    # ice crystal sparkle
    d.regular_polygon((212, 150, 9), 6, fill=(200, 240, 255, 255))

    OUT.parent.mkdir(exist_ok=True)
    img.save(OUT, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    img.save(OUT.with_suffix(".png"))
    print(OUT)


if __name__ == "__main__":
    main()
