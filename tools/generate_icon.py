#!/usr/bin/env python3
"""Generate assets/icons/aipilot.ico — detailed Platforma151-style AIP icon."""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "assets" / "icons"
OUT_ICO = OUT / "aipilot.ico"
OUT_PNG = OUT / "aipilot.png"
RES_PNG = ROOT / "src" / "main" / "resources" / "icons" / "aipilot.png"

BG_OUTER = (0x0A, 0x0B, 0x0E, 255)
BG_INNER = (0x12, 0x15, 0x1A, 255)
GOLD = (0xA8, 0x97, 0x6A, 255)
GOLD_DIM = (0x6B, 0x5F, 0x45, 255)
FG = (255, 255, 255, 255)
SHADOW = (0, 0, 0, 90)

_GLYPHS = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
}


def _blend(dst: list[int], x: int, y: int, w: int, r: int, g: int, b: int, a: int) -> None:
    if x < 0 or y < 0 or x >= w or y >= w or a <= 0:
        return
    i = (y * w + x) * 4
    if a >= 255:
        dst[i : i + 4] = [r, g, b, 255]
        return
    oa = dst[i + 3]
    if oa == 0:
        dst[i : i + 4] = [r, g, b, a]
        return
    na = a + oa * (255 - a) // 255
    inv = 255 - a
    dst[i] = (r * a + dst[i] * inv) // 255
    dst[i + 1] = (g * a + dst[i + 1] * inv) // 255
    dst[i + 2] = (b * a + dst[i + 2] * inv) // 255
    dst[i + 3] = na


def _inside_round_rect(x: int, y: int, w: int, rad: int) -> bool:
    cx = min(x, w - 1 - x)
    cy = min(y, w - 1 - y)
    if cx >= rad or cy >= rad:
        return True
    dx = rad - 1 - cx
    dy = rad - 1 - cy
    return dx * dx + dy * dy <= rad * rad


def _fill_background(px: list[int], w: int, rad: int) -> None:
    cx = cy = (w - 1) / 2.0
    max_d = (w / 2) * 1.15
    for y in range(w):
        for x in range(w):
            if not _inside_round_rect(x, y, w, rad):
                continue
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            t = min(1.0, d / max_d)
            r = int(BG_OUTER[0] * (1 - t) + BG_INNER[0] * t)
            g = int(BG_OUTER[1] * (1 - t) + BG_INNER[1] * t)
            b = int(BG_OUTER[2] * (1 - t) + BG_INNER[2] * t)
            _blend(px, x, y, w, r, g, b, 255)


def _stroke_border(px: list[int], w: int, rad: int, color: tuple[int, int, int, int], thickness: int) -> None:
    r, g, b, a = color
    for y in range(w):
        for x in range(w):
            if not _inside_round_rect(x, y, w, rad):
                continue
            inner = _inside_round_rect(x, y, w, max(1, rad - thickness))
            if not inner:
                _blend(px, x, y, w, r, g, b, a)


def _draw_accent_line(px: list[int], w: int, rad: int) -> None:
    y = w - max(2, w // 8)
    x0 = rad + max(1, w // 16)
    x1 = w - rad - max(1, w // 16)
    for x in range(x0, x1):
        _blend(px, x, y, w, *GOLD)
        if y + 1 < w:
            _blend(px, x, y + 1, w, *GOLD_DIM)


def _draw_text(px: list[int], w: int, text: str, color: tuple[int, int, int, int], shadow: bool) -> None:
    scale = max(1, w // 16)
    cell_w = 5 * scale
    cell_h = 7 * scale
    gap = scale
    total_w = len(text) * cell_w + (len(text) - 1) * gap
    ox = (w - total_w) // 2
    oy = (w - cell_h) // 2 - max(0, w // 32)
    r, g, b, a = color
    sr, sg, sb, sa = SHADOW
    for gi, ch in enumerate(text):
        rows = _GLYPHS.get(ch)
        if not rows:
            continue
        gx = ox + gi * (cell_w + gap)
        for ry, row in enumerate(rows):
            for rx, bit in enumerate(row):
                if bit != "1":
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        px_x = gx + rx * scale + sx
                        px_y = oy + ry * scale + sy
                        if shadow:
                            _blend(px, px_x + max(1, scale // 2), px_y + max(1, scale // 2), w, sr, sg, sb, sa)
                        _blend(px, px_x, px_y, w, r, g, b, a)


def render(size: int) -> bytes:
    px = [0] * (size * size * 4)
    rad = max(2, size // 6)
    _fill_background(px, size, rad)
    border_w = max(1, size // 128)
    _stroke_border(px, size, rad, GOLD_DIM, border_w + 1)
    _stroke_border(px, size, rad, GOLD, border_w)
    _draw_text(px, size, "AIP", FG, shadow=size >= 32)
    if size >= 48:
        _draw_accent_line(px, size, rad)
    return bytes(px)


def write_png(path: Path, size: int, rgba: bytes) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b""
    stride = size * 4
    for y in range(size):
        raw += b"\x00" + rgba[y * stride : (y + 1) * stride]
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def write_ico(path: Path, sizes: list[int]) -> None:
    images: list[bytes] = []
    for s in sizes:
        rgba = render(s)
        tmp = path.with_suffix(f".{s}.png.tmp")
        write_png(tmp, s, rgba)
        images.append(tmp.read_bytes())
        tmp.unlink(missing_ok=True)
    count = len(sizes)
    header = struct.pack("<HHH", 0, 1, count)
    offset = 6 + 16 * count
    entries = b""
    payloads = b""
    for s, data in zip(sizes, images):
        w = 0 if s >= 256 else s
        h = 0 if s >= 256 else s
        entries += struct.pack("<BBBBHHII", w, h, 0, 0, 1, 32, len(data), offset)
        payloads += data
        offset += len(data)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(header + entries + payloads)


def main() -> None:
    sizes = [16, 32, 48, 64, 128, 256]
    write_ico(OUT_ICO, sizes)
    rgba256 = render(256)
    write_png(OUT_PNG, 256, rgba256)
    write_png(RES_PNG, 256, rgba256)
    print("Wrote", OUT_ICO, OUT_PNG, RES_PNG)


if __name__ == "__main__":
    main()
