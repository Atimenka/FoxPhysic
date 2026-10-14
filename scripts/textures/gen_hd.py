"""Procedural HD (128x128) textures for FoxPhys: assembler, gravity gun, physics water bucket.

Run: python3 scripts/textures/gen_hd.py   (writes into neoforge/src/main/resources/assets/foxphys/textures)
Uniform UV space in Minecraft is 16 units, so 128 px textures are sampled 8 px per unit.
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 128
ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "neoforge", "src", "main", "resources",
                    "assets", "foxphys", "textures")
rng = np.random.default_rng(20261012)


def fbm(octaves=5, base=4, persistence=0.55, shape=(S, S)):
    """Fractal value noise in [0,1], tileable (wrap via resize of a periodic grid)."""
    out = np.zeros(shape)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        n = base * (2 ** o)
        grid = rng.random((n, n))
        img = Image.fromarray((grid * 255).astype(np.uint8)).resize(shape[::-1], Image.BICUBIC)
        out += amp * (np.asarray(img, dtype=np.float64) / 255.0)
        total += amp
        amp *= persistence
    out /= total
    out = (out - out.min()) / (out.max() - out.min() + 1e-9)
    return out


def hstreaks(strength=0.08):
    """Brushed-metal streaks: horizontal 1D noise, smoothed along x."""
    rows = rng.random(S)
    rows = np.convolve(rows, np.ones(3) / 3, mode="same")
    fine = rng.random((S, S))
    img = np.repeat(rows[:, None], S, axis=1) * 0.7 + fine * 0.3
    return (img - 0.5) * strength * 2


def to_rgb(arr):
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")


def bevel(img, width=3, light=38, dark=60):
    """Simple top-left highlight and bottom-right shade to give panel edges depth."""
    a = img.copy()
    px = a.load()
    for x in range(S):
        for y in range(S):
            if x < width or y < width:
                r, g, b, al = px[x, y]
                px[x, y] = (min(255, r + light), min(255, g + light), min(255, b + light), al)
            if x >= S - width or y >= S - width:
                r, g, b, al = px[x, y]
                px[x, y] = (max(0, r - dark), max(0, g - dark), max(0, b - dark), al)
    return a


def rivet(draw, cx, cy, r=2.2):
    draw.ellipse([cx - r - 0.8, cy - r - 0.8, cx + r + 0.8, cy + r + 0.8], fill=(20, 22, 26, 255))
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(150, 156, 166, 255))
    draw.ellipse([cx - r * 0.4 - 0.6, cy - r * 0.4 - 0.6, cx - r * 0.2, cy - r * 0.2], fill=(235, 238, 242, 255))


def steel(base_rgb, grime=0.5, seed_shift=0.0):
    n = fbm(5, 4, 0.55)
    coarse = fbm(3, 2, 0.6)
    s = hstreaks(0.10)
    v = 0.55 + 0.35 * (n - 0.5) + 0.25 * (coarse - 0.5) + s
    # bottom-heavy grime
    yy = np.linspace(0, 1, S)[:, None]
    dirt = np.clip((n - 0.45) * 2.5, 0, 1) * (0.4 + 0.6 * yy)
    v = v - grime * 0.22 * dirt
    base = np.array(base_rgb, dtype=np.float64)[None, None, :]
    rgb = v[..., None] * base / 0.6
    rgb = rgb + seed_shift
    a = np.full((S, S, 1), 255.0)
    return np.concatenate([rgb, a], axis=2)


def panel_frame(img, inset=6, step=None, color=(18, 20, 24, 255)):
    d = ImageDraw.Draw(img)
    d.rectangle([inset, inset, S - 1 - inset, S - 1 - inset], outline=color, width=2)
    return img


def assembler_top():
    base = steel((92, 104, 118), grime=0.4)
    img = to_rgb(base)
    d = ImageDraw.Draw(img)
    # Recessed hatch with diagonal hazard stripes and a central lock
    d.rectangle([22, 22, 105, 105], fill=(58, 64, 72, 255), outline=(20, 22, 26, 255), width=2)
    for k in range(-8, 16):
        x0 = 22 + k * 10
        d.polygon([(x0, 105), (x0 + 5, 105), (x0 + 5 + 42, 22), (x0 + 42, 22)], fill=(214, 170, 36, 255))
    d.rectangle([22, 22, 105, 105], outline=(20, 22, 26, 255), width=2)
    d.rectangle([54, 54, 73, 73], fill=(120, 128, 138, 255), outline=(20, 22, 26, 255), width=2)
    d.ellipse([60, 60, 67, 67], fill=(38, 200, 110, 255))
    for x, y in [(10, 10), (118, 10), (10, 118), (118, 118)]:
        rivet(d, x, y)
    img = bevel(img, 3, 30, 50)
    img = panel_frame(img, 4)
    return img


def assembler_side():
    base = steel((78, 92, 108), grime=0.6)
    img = to_rgb(base)
    d = ImageDraw.Draw(img)
    # Vertical ribs and horizontal seams
    for x in (30, 64, 98):
        d.line([(x, 0), (x, S)], fill=(34, 40, 48, 255), width=2)
        d.line([(x + 2, 0), (x + 2, S)], fill=(150, 160, 172, 120), width=1)
    for y in (46, 92):
        d.line([(0, y), (S, y)], fill=(30, 34, 40, 255), width=2)
    # Vent slots on lower band
    for x in range(12, 120, 9):
        d.rounded_rectangle([x, 100, x + 4, 112], radius=1, fill=(20, 22, 26, 255))
    for x, y in [(10, 10), (118, 10), (10, 118), (118, 118), (64, 36), (64, 56)]:
        rivet(d, x, y, 1.8)
    # Warning stripe near top
    d.rectangle([0, 0, S, 5], fill=(214, 170, 36, 255))
    d.line([(0, 2), (S, 2)], fill=(30, 26, 10, 255), width=1)
    img = bevel(img, 2, 22, 40)
    return img


def assembler_bottom():
    base = steel((48, 52, 58), grime=0.9)
    img = to_rgb(base)
    d = ImageDraw.Draw(img)
    for x, y in [(12, 12), (116, 12), (12, 116), (116, 116)]:
        rivet(d, x, y, 2.4)
    d.rectangle([34, 34, 93, 93], outline=(30, 34, 40, 255), width=3)
    for k in range(4):
        d.line([(34 + k * 20, 34), (34 + k * 20, 93)], fill=(60, 66, 74, 255), width=1)
    return bevel(img, 2, 14, 30)


def assembler_front():
    """Control panel on one face: gauges, status lights and a small screen."""
    base = steel((72, 86, 100), grime=0.35)
    img = to_rgb(base)
    d = ImageDraw.Draw(img)
    # Screen
    d.rounded_rectangle([18, 18, 110, 58], radius=4, fill=(10, 30, 26, 255), outline=(16, 18, 22, 255), width=3)
    for i, y in enumerate(range(26, 52, 6)):
        w = 40 + (i * 13) % 50
        d.rectangle([24, y, 24 + w, y + 2], fill=(90, 230, 170, 255))
    # Gauges
    for cx in (34, 64, 94):
        d.ellipse([cx - 11, 74, cx + 11, 96], fill=(230, 232, 236, 255), outline=(20, 22, 26, 255), width=2)
        d.line([(cx, 85), (cx + 7, 78)], fill=(190, 40, 40, 255), width=2)
        d.ellipse([cx - 1.5, 83.5, cx + 1.5, 86.5], fill=(30, 30, 34, 255))
    # Status lights and a big start button
    for i, c in enumerate([(60, 220, 110, 255), (240, 190, 40, 255), (230, 70, 60, 255)]):
        x = 22 + i * 12
        d.ellipse([x, 106, x + 7, 113], fill=c, outline=(20, 22, 26, 255))
    d.ellipse([82, 104, 106, 124], fill=(200, 40, 40, 255), outline=(20, 22, 26, 255), width=2)
    for x, y in [(10, 10), (118, 10), (10, 118), (118, 118)]:
        rivet(d, x, y, 1.8)
    return bevel(img, 2, 26, 44)


def gravity_gun():
    """Gunmetal receiver, copper coil, glowing emitter ring."""
    base = steel((70, 76, 86), grime=0.3)
    img = to_rgb(base)
    d = ImageDraw.Draw(img)
    # Copper coil bands
    for x in range(44, 80, 9):
        d.rectangle([x, 20, x + 4, 108], fill=(176, 104, 58, 255), outline=(90, 48, 24, 255))
        d.line([(x + 1, 20), (x + 1, 108)], fill=(240, 170, 110, 255), width=1)
    # Grip texture (diagonal knurl)
    for k in range(0, 128, 6):
        d.line([(k, 128), (k + 40, 80)], fill=(40, 44, 52, 255), width=1)
    # Emitter ring with glow
    d.ellipse([100, 36, 124, 92], fill=(40, 170, 220, 255), outline=(16, 22, 30, 255), width=3)
    d.ellipse([106, 44, 118, 84], fill=(190, 240, 255, 255))
    # Panel lines and rivets
    d.line([(8, 52), (96, 52)], fill=(30, 34, 40, 255), width=2)
    d.line([(8, 78), (96, 78)], fill=(30, 34, 40, 255), width=2)
    for x, y in [(12, 40), (88, 40), (12, 90), (88, 90)]:
        rivet(d, x, y, 2.0)
    return bevel(img, 2, 24, 40)


def water_bucket_metal():
    """Galvanised bucket with a painted blue band and rim."""
    base = steel((150, 158, 170), grime=0.2)
    img = to_rgb(base)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 58, S, 74], fill=(40, 110, 170, 255))
    d.line([(0, 58), (S, 58)], fill=(18, 60, 96, 255), width=2)
    d.line([(0, 74), (S, 74)], fill=(18, 60, 96, 255), width=2)
    for x in range(0, S, 16):
        d.line([(x, 0), (x, S)], fill=(120, 126, 136, 90), width=1)
    return bevel(img, 2, 26, 40)


def water_surface():
    """Water seen from above: deep-blue body, caustic light lines, semi-transparent edge."""
    h = fbm(5, 3, 0.6)
    yy, xx = np.mgrid[0:S, 0:S] / S
    warp = np.sin((xx * 6.2 + h * 3.0) * 2 * np.pi) * 0.5 + np.sin((yy * 5.1 - h * 2.5) * 2 * np.pi) * 0.5
    caustic = np.exp(-((warp - 0.0) ** 2) * 40.0)
    depth = 0.55 + 0.45 * h
    r = 14 + 20 * depth + 15 * caustic
    g = 84 + 60 * depth + 60 * caustic
    b = 150 + 70 * depth + 60 * caustic
    edge = np.minimum(np.minimum(xx, 1 - xx), np.minimum(yy, 1 - yy))
    alpha = np.clip(150 + 90 * edge * 4 + 40 * caustic, 0, 230)
    arr = np.stack([np.clip(r, 0, 255), np.clip(g, 0, 255), np.clip(b, 0, 255), alpha], axis=2)
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def save(img, name):
    path = os.path.join(ROOT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path))


if __name__ == "__main__":
    blk = os.path.join("block")
    itm = os.path.join("item")
    save(assembler_top(), f"{blk}/physics_assembler_top.png")
    save(assembler_side(), f"{blk}/physics_assembler_side.png")
    save(assembler_bottom(), f"{blk}/physics_assembler_bottom.png")
    save(assembler_front(), f"{blk}/physics_assembler_front.png")
    save(gravity_gun(), f"{itm}/gravity_gun.png")
    save(water_bucket_metal(), f"{itm}/physics_water_bucket.png")
    save(water_surface(), f"{itm}/physics_water_surface.png")
