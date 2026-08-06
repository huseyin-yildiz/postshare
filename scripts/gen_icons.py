#!/usr/bin/env python3
"""Generate launcher PNG icons (green gradient + white paper plane).

Uses only the Python standard library to emit PNG files.
The paper plane is the Material "send" glyph, scaled to fit nicely.
"""
import os
import struct
import zlib


def png_chunk(tag: bytes, data: bytes) -> bytes:
    c = struct.pack(">I", len(data)) + tag + data
    c += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return c


def write_png(path: str, size: int, pixel_fn) -> None:
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # filter type: none
        for x in range(size):
            raw += bytes(pixel_fn(x, y))
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    data = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr)
    data += png_chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    data += png_chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


# Material "send" paper plane, 24x24 viewport.
PLANE = [(2, 21), (23, 12), (2, 3), (2, 10), (17, 12), (2, 14)]


def point_in_polygon(px, py, poly) -> bool:
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def make_pixel_fn(size: int):
    top = (0x25, 0xD3, 0x66)  # #25D366
    bottom = (0x07, 0x5E, 0x54)  # #075E54
    plane_white = (255, 255, 255)

    # Map the 24x24 plane onto ~52% of the icon, centered at (0.52, 0.50).
    s = 0.52 * size / 21.0
    ox = 0.52 * size - 12.5 * s
    oy = 0.50 * size - 12.0 * s

    def pixel(x, y):
        t = (x + y) / (2.0 * (size - 1))  # diagonal gradient
        color = lerp(top, bottom, t)
        if point_in_polygon((x - ox) / s, (y - oy) / s, PLANE):
            color = plane_white
        return (color[0], color[1], color[2], 255)

    return pixel


def main():
    out_dir = "app/src/main/res"
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, size in sizes.items():
        os.makedirs(f"{out_dir}/{folder}", exist_ok=True)
        write_png(f"{out_dir}/{folder}/ic_launcher.png", size, make_pixel_fn(size))
        print(f"wrote {out_dir}/{folder}/ic_launcher.png ({size}x{size})")


if __name__ == "__main__":
    main()
