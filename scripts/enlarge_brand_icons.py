#!/usr/bin/env python3
"""Generate web, desktop and Android branding from branding/aoede.png."""

from pathlib import Path

from PIL import Image, ImageChops, ImageOps

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "Aoede/assets" if (ROOT / "Aoede").is_dir() else ROOT / "frontend-source/assets"
ANDROID = ROOT / "android/app/src/main/res" if (ROOT / "android").is_dir() else ROOT / "app/src/main/res"


def main():
    source = Image.open(ROOT / "branding/aoede.png").convert("RGBA")
    bounds = source.getchannel("A").point(lambda alpha: 255 if alpha > 32 else 0).getbbox()
    if bounds is None:
        raise ValueError("The Aoede master icon is empty")
    source = source.crop(bounds)

    def render(size, box, artwork=source):
        left, top, right, bottom = box
        scaled = ImageOps.contain(artwork, (right - left, bottom - top), Image.Resampling.LANCZOS)
        icon = Image.new("RGBA", (size, size))
        icon.alpha_composite(
            scaled, (left + (right - left - scaled.width) // 2, top + (bottom - top - scaled.height) // 2)
        )
        return icon

    # Keep the existing pixel sizes, visual bounds and alignment in each client.
    render(1254, (118, 110, 1137, 1091)).save(WEB / "icon.png", optimize=True)
    desktop = ROOT / "desktop/resources/icons/appIcon.png"
    if desktop.parent.is_dir():
        render(1024, (96, 90, 928, 891)).save(desktop, optimize=True)
    icons = {}
    for size in (16, 32, 48, 64, 128, 180, 192, 256, 512, 1024):
        side = min(size - 2, round(size * 0.92))
        margin = (size - side) // 2
        icons[size] = render(size, (margin, margin, margin + side, margin + side))
    for size in (16, 32):
        icons[size].save(WEB / f"favicon-{size}x{size}.png", optimize=True)
    icons[256].save(
        WEB / "favicon.ico",
        sizes=[(s, s) for s in (16, 32, 48, 64, 128, 256)],
        append_images=[icons[s] for s in (16, 32, 48, 64, 128)],
    )
    for size in (192, 512):
        icons[size].save(WEB / f"android-chrome-{size}x{size}.png", optimize=True)
    # Safari Add to Dock needs a high-resolution, opaque maskable icon. Its
    # background reaches every edge; the colored mark stays in the safe zone.
    for size, name in ((180, "apple-touch-icon.png"), (1024, "app-icon-maskable-1024.png")):
        opaque = Image.new("RGBA", (size, size), "#181b2a")
        opaque.alpha_composite(icons[size])
        opaque.convert("RGB").save(WEB / name, optimize=True)

    # The supplied silver A/waveform is brighter than its dark glass tile.
    # Extract it for Android's flat, system-tinted notification and themed icons.
    mask = ImageChops.multiply(
        ImageOps.grayscale(source).point(lambda value: 255 if value > 160 else 0), source.getchannel("A")
    )
    mark = Image.new("RGBA", source.size, "white")
    mark.putalpha(mask)
    if mask.getbbox() is None:
        raise ValueError("The Aoede monochrome mark is empty")
    notification = mark.crop(mask.getbbox())
    resources = ANDROID
    densities = (
        ("mdpi", 48, (6, 6, 42, 40), 24),
        ("hdpi", 72, (10, 9, 62, 60), 36),
        ("xhdpi", 96, (13, 12, 83, 80), 48),
        ("xxhdpi", 144, (20, 19, 124, 120), 72),
        ("xxxhdpi", 192, (26, 25, 166, 159), 96),
    )
    for density, size, box, small in densities:
        folder = resources / f"mipmap-{density}"
        icon = render(size, box)
        for name in ("ic_launcher", "ic_launcher_round", "ic_launcher_foreground"):
            icon.save(folder / f"{name}.png", optimize=True)
        render(size, box, mark).save(folder / "ic_launcher_monochrome.png", optimize=True)
        folder = resources / f"drawable-{density}"
        folder.mkdir(exist_ok=True)
        inset = small // 8
        render(small, (inset, inset, small - inset, small - inset), notification).save(
            folder / "ic_notification.png", optimize=True
        )


if __name__ == "__main__":
    main()
