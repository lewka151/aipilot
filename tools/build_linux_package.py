#!/usr/bin/env python3
"""Build dist/aipilot-linux.tar.gz with app-image + install scripts."""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / "dist"
APP_IMAGE = DIST / "AIPilot"
OUT_TAR = DIST / "aipilot-linux.tar.gz"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    linux_bin = APP_IMAGE / "bin" / "AIPilot"
    if not linux_bin.is_file() and os.name != "nt":
        raise SystemExit(f"Missing Linux app-image: {linux_bin} (run ./gradlew packageLinuxApp)")
    if os.name == "nt":
        print("WARN: building linux tar on Windows — ensure dist/AIPilot exists from Linux build")

    staging = DIST / "aipilot-linux-staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    if APP_IMAGE.is_dir():
        shutil.copytree(APP_IMAGE, staging / "AIPilot")

    for name in ("install-linux.sh", "uninstall-linux.sh", "run-headless-installed.sh"):
        src = ROOT / "tools" / name
        dest_name = name.replace("-linux", "").replace("-installed", "")
        if name == "install-linux.sh":
            dest_name = "install.sh"
        elif name == "uninstall-linux.sh":
            dest_name = "uninstall.sh"
        elif name == "run-headless-installed.sh":
            dest_name = "run-headless.sh"
        text = src.read_text(encoding="utf-8").replace("\r\n", "\n")
        dest = staging / dest_name
        dest.write_text(text, encoding="utf-8")
        dest.chmod(0o755)

    readme = staging / "README-LINUX.md"
    readme.write_text(
        "# AIPilot Linux (Ubuntu 26)\n\n"
        "```bash\n"
        "tar -xzf aipilot-linux.tar.gz\n"
        "cd aipilot-linux\n"
        "./install.sh          # ~/.local/share/AIPilot + ~/.local/bin/aipilot\n"
        "aipilot               # desktop UI\n"
        "./run-headless.sh     # headless worker (xvfb)\n"
        "./uninstall.sh\n"
        "```\n\n"
        "Config: `~/.config/aipilot/`. Bundled JRE — separate Java install not required.\n"
        "Ubuntu 26.04: GTK3 is `libgtk-3-0t64`; Wayland needs XWayland (`GDK_BACKEND=x11`).\n",
        encoding="utf-8",
    )

    if OUT_TAR.exists():
        OUT_TAR.unlink()
    with tarfile.open(OUT_TAR, "w:gz") as tar:
        def _tar_filter(ti: tarfile.TarInfo) -> tarfile.TarInfo:
            name = ti.name.replace("\\", "/")
            base = name.rsplit("/", 1)[-1]
            if ti.isfile() and (
                base in ("AIPilot", "AIPilot.bin", "aipilot", "java", "jexec")
                or name.endswith(".sh")
                or "/bin/" in name
            ):
                ti.mode = 0o755
            return ti

        tar.add(staging, arcname="aipilot-linux", filter=_tar_filter)

    (DIST / "aipilot-linux.tar.gz.sha256").write_text(
        sha256_file(OUT_TAR) + "  aipilot-linux.tar.gz\n", encoding="utf-8"
    )
    shutil.rmtree(staging)
    print("Wrote", OUT_TAR)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
