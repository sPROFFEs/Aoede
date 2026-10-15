#!/usr/bin/env python3
"""Regenerate browser/PWA icons from the unchanged Aoede master artwork."""

from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]


def main():
    source = Image.open(ROOT / "Aoede/assets/icon.png").convert("RGBA")
    bounds = source.getchannel("A").point(lambda alpha: 255 if alpha > 32 else 0).getbbox()
    if bounds is None:
        raise ValueError("The Aoede master icon is empty")
    source = source.crop(bounds)
    icons = {}
    for size in (16, 32, 48, 64, 128, 180, 192, 256, 512, 1024):
        side = min(size - 2, round(size * 0.92))
        scaled = ImageOps.contain(source, (side, side), Image.Resampling.LANCZOS)
        icon = Image.new("RGBA", (size, size))
        icon.alpha_composite(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2))
        icons[size] = icon
    for size in (16, 32):
        icons[size].save(ROOT / f"Aoede/assets/favicon-{size}x{size}.png")
    icons[256].save(
        ROOT / "Aoede/assets/favicon.ico",
        sizes=[(s, s) for s in (16, 32, 48, 64, 128, 256)],
        append_images=[icons[s] for s in (16, 32, 48, 64, 128)],
    )
    for size in (192, 512):
        icons[size].save(ROOT / f"Aoede/assets/android-chrome-{size}x{size}.png")
    # Safari Add to Dock needs a high-resolution, opaque maskable icon. Its
    # background reaches every edge; the colored mark stays in the safe zone.
    for size, name in ((180, "apple-touch-icon.png"), (1024, "app-icon-maskable-1024.png")):
        opaque = Image.new("RGBA", (size, size), "#181b2a")
        opaque.alpha_composite(icons[size])
        opaque.convert("RGB").save(ROOT / "Aoede/assets" / name)


if __name__ == "__main__":
    main()
