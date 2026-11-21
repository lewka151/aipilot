from pathlib import Path

from PIL import Image, ImageDraw

BG = (12, 14, 18, 255)
GOLD = (168, 151, 106, 255)


def mark(size: int) -> Image.Image:
    im = Image.new("RGBA", (size, size), BG)
    d = ImageDraw.Draw(im)
    m = max(2, size // 8)
    w = max(1, size // 18)
    d.rounded_rectangle(
        (m, m, size - m - 1, size - m - 1),
        radius=max(2, size // 8),
        outline=GOLD,
        width=w,
    )
    cx = cy = size / 2
    r = max(2, size // 10)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=GOLD)
    stem = max(1, size // 20)
    d.rectangle((cx - stem / 2, cy + r * 0.2, cx + stem / 2, size - m - w), fill=GOLD)
    return im


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    out_dirs = [root / "assets" / "icons", root / "src" / "main" / "resources" / "icons"]
    for folder in out_dirs:
        folder.mkdir(parents=True, exist_ok=True)
    images = {size: mark(size) for size in (16, 32, 48, 256)}
    for folder in out_dirs:
        images[256].save(folder / "aipilot.png", "PNG")
        for size in (16, 32, 48):
            images[size].save(folder / f"aipilot-{size}.png", "PNG")
    ico = root / "assets" / "icons" / "aipilot.ico"
    images[256].save(ico, format="ICO", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)])
    print("wrote", ico)


if __name__ == "__main__":
    main()
