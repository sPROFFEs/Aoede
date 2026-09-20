#!/usr/bin/env python3
"""Regenerate Aoede's raster icons with a slightly tighter visual margin."""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Aoede/assets/icon.png"
SCALE = 1.08


def read_png(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG: {path}")
    pos = 8
    width = height = None
    compressed = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        chunk = data[pos + 8 : pos + 8 + length]
        pos += length + 12
        if kind == b"IHDR":
            width, height, depth, color_type, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", chunk
            )
            if (depth, color_type, compression, filtering, interlace) != (8, 6, 0, 0, 0):
                raise ValueError("source must be a non-interlaced RGBA PNG")
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break
    if width is None or height is None:
        raise ValueError("PNG has no dimensions")

    raw = zlib.decompress(compressed)
    stride = width * 4
    rows: list[bytearray] = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        encoded = raw[offset + 1 : offset + 1 + stride]
        offset += stride + 1
        row = bytearray(stride)
        for i, value in enumerate(encoded):
            left = row[i - 4] if i >= 4 else 0
            above = previous[i]
            upper_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = above
            elif filter_type == 3:
                predictor = (left + above) // 2
            elif filter_type == 4:
                estimate = left + above - upper_left
                distances = (abs(estimate - left), abs(estimate - above), abs(estimate - upper_left))
                predictor = (left, above, upper_left)[distances.index(min(distances))]
            else:
                raise ValueError(f"unsupported PNG filter: {filter_type}")
            row[i] = (value + predictor) & 0xFF
        rows.append(row)
        previous = row
    return width, height, b"".join(rows)


def write_png(path: Path, width: int, height: int, pixels: bytes) -> None:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)

    scanlines = b"".join(b"\x00" + pixels[y * width * 4 : (y + 1) * width * 4] for y in range(height))
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(scanlines, 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


def resize(source: bytes, source_width: int, source_height: int, size: int) -> bytes:
    output = bytearray(size * size * 4)
    scale = SCALE * size / source_width
    center = (size - 1) / 2
    source_center_x = (source_width - 1) / 2
    source_center_y = (source_height - 1) / 2
    for y in range(size):
        sy = (y - center) / scale + source_center_y
        y0 = int(sy)
        fy = sy - y0
        for x in range(size):
            sx = (x - center) / scale + source_center_x
            x0 = int(sx)
            fx = sx - x0
            destination = (y * size + x) * 4
            if x0 < 0 or y0 < 0 or x0 >= source_width - 1 or y0 >= source_height - 1:
                continue
            for channel in range(4):
                top = source[(y0 * source_width + x0) * 4 + channel] * (1 - fx) + source[(y0 * source_width + x0 + 1) * 4 + channel] * fx
                bottom = source[((y0 + 1) * source_width + x0) * 4 + channel] * (1 - fx) + source[((y0 + 1) * source_width + x0 + 1) * 4 + channel] * fx
                output[destination + channel] = round(top * (1 - fy) + bottom * fy)
    return bytes(output)


def make_ico(path: Path, pngs: list[tuple[int, bytes]]) -> None:
    header = struct.pack("<HHH", 0, 1, len(pngs))
    entries = bytearray()
    payload = bytearray()
    offset = 6 + 16 * len(pngs)
    for size, image in pngs:
        dimension = 0 if size >= 256 else size
        entries.extend(struct.pack("<BBBBHHII", dimension, dimension, 0, 0, 1, 32, len(image), offset))
        payload.extend(image)
        offset += len(image)
    path.write_bytes(header + entries + payload)


def main() -> None:
    source_width, source_height, source = read_png(SOURCE)
    outputs = {
        "Aoede/assets/icon.png": 1254,
        "Aoede/assets/android-chrome-192x192.png": 192,
        "Aoede/assets/android-chrome-512x512.png": 512,
        "Aoede/assets/apple-touch-icon.png": 180,
        "Aoede/assets/favicon-16x16.png": 16,
        "Aoede/assets/favicon-32x32.png": 32,
        "desktop/resources/icons/appIcon.png": 1024,
    }
    generated: dict[int, bytes] = {}
    for relative, size in outputs.items():
        pixels = resize(source, source_width, source_height, size)
        target = ROOT / relative
        write_png(target, size, size, pixels)
        generated[size] = target.read_bytes()
    ico_images = []
    for size in (16, 32, 48, 64, 128, 256):
        pixels = resize(source, source_width, source_height, size)
        temp = ROOT / f".favicon-{size}.png"
        write_png(temp, size, size, pixels)
        ico_images.append((size, temp.read_bytes()))
        temp.unlink()
    make_ico(ROOT / "Aoede/assets/favicon.ico", ico_images)


if __name__ == "__main__":
    sys.exit(main())
