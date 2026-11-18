"""Draws the Play Store icon (512x512) and feature graphics (1024x500) from the app's
launcher icon shapes. Run: python3 tools/store/make_graphics.py  (needs Pillow)."""
import os
from PIL import Image, ImageDraw, ImageFont

ROSSO = (0xC6, 0x28, 0x28)
DARK = (0x12, 0x12, 0x14)
BUTTON = (0x2A, 0x2A, 0x2E)
WHITE = (255, 255, 255)
MUTED = (0xB8, 0xB8, 0xC0)
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "store", "graphics")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def remote_glyph(draw, ox, oy, scale):
    """The launcher foreground (108x108 viewport), drawn at (ox, oy) with the given scale."""
    s = lambda v: v * scale
    draw.rounded_rectangle([ox + s(38), oy + s(30), ox + s(70), oy + s(78)], radius=s(6), fill=WHITE)
    draw.ellipse([ox + s(48), oy + s(32), ox + s(60), oy + s(44)], fill=ROSSO)
    for x, y in [(45, 52), (57, 52), (45, 61), (57, 61)]:
        draw.rectangle([ox + s(x), oy + s(y), ox + s(x + 6), oy + s(y + 5)], fill=ROSSO)


def icon(size=512, ss=4):
    big = size * ss
    img = Image.new("RGB", (big, big), ROSSO)
    # The adaptive icon shows the middle 72 of the 108 viewport.
    scale = big / 72
    remote_glyph(ImageDraw.Draw(img), -18 * scale, -18 * scale, scale)
    return img.resize((size, size), Image.LANCZOS)


def feature(title, subtitle, name, ss=2):
    w, h = 1024 * ss, 500 * ss
    img = Image.new("RGB", (w, h), DARK)
    d = ImageDraw.Draw(img)
    tile = 300 * ss
    tx, ty = 90 * ss, (h - tile) // 2
    ic = icon(tile, 1)
    mask = Image.new("L", (tile, tile), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, tile, tile], radius=tile // 4, fill=255)
    img.paste(ic, (tx, ty), mask)
    x = tx + tile + 70 * ss
    title_font = ImageFont.truetype(FONT, 64 * ss)
    sub_font = ImageFont.truetype(FONT_REGULAR, 30 * ss)
    d.text((x, 165 * ss), title, font=title_font, fill=WHITE)
    y = 260 * ss
    for line in subtitle.split("\n"):
        d.text((x, y), line, font=sub_font, fill=MUTED)
        y += 42 * ss
    img.resize((1024, 500), Image.LANCZOS).save(os.path.join(OUT, name))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    icon().save(os.path.join(OUT, "icon-512.png"))
    feature("TV Remote", "for Rosso, Android TV\nand Google TV", "feature-en.png")
    feature("ТВ-пульт", "для Rosso, Android TV\nи Google TV", "feature-ru.png")
    feature("TV pulti", "Rosso, Android TV\nva Google TV uchun", "feature-uz.png")
