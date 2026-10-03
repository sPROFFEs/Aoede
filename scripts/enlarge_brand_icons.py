#!/usr/bin/env python3
"""Regenerate crisp favicons with less transparent padding; keep the master intact."""

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
    for size in (16, 32, 48, 64, 128, 256):
        side = min(size - 2, round(size * 0.92))
        scaled = ImageOps.contain(source, (side, side), Image.Resampling.LANCZOS)
        icon = Image.new("RGBA", (size, size))
        icon.alpha_composite(scaled, ((size - scaled.width) // 2, (size - scaled.height) // 2))
        icons[size] = icon
    for size in (16, 32):
        icons[size].save(ROOT / f"Aoede/assets/favicon-{size}x{size}.png")
    icons[256].save(
        ROOT / "Aoede/assets/favicon.ico",
        sizes=[(s, s) for s in icons],
        append_images=[icons[s] for s in icons if s != 256],
    )


if __name__ == "__main__":
    main()
