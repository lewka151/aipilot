#!/usr/bin/env python3
"""Extract candidate DOM selectors from prompt/ HTML/MHTML snapshots."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


SEND_PATTERNS = (
    r'role="button"[^>]*ds-button--iconLabelPrimary',
    r'message-input-right-button-send',
    r'send-button',
    r'type="submit"',
)
INPUT_PATTERNS = (
    r'textarea[^>]*message-input',
    r'textarea[^>]*ds-scroll-area',
    r'textarea[^>]*ds-textarea',
)
EXPERT_PATTERNS = (
    r'data-model-type="expert"',
    r'deepthink',
    r'ds-toggle-button',
)
CHAT_PATTERNS = (
    r'project-chat-item-title',
    r'chat-item-drag-link-content-tip-text',
    r'project-chat-item',
)


def read_snapshot(path: Path) -> str:
    raw = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix.lower() == ".mhtml":
        # MHTML often stores HTML chunks quoted-printable; keep tags intact enough for regex.
        raw = raw.replace("=\r\n", "").replace("=\n", "")
    return raw


def find_hints(text: str, patterns: tuple[str, ...], limit: int = 8) -> list[str]:
    hits: list[str] = []
    for pat in patterns:
        for m in re.finditer(pat, text, flags=re.IGNORECASE):
            start = max(0, m.start() - 120)
            end = min(len(text), m.end() + 120)
            snippet = re.sub(r"\s+", " ", text[start:end]).strip()
            if snippet and snippet not in hits:
                hits.append(snippet)
            if len(hits) >= limit:
                return hits
    return hits


def analyze_file(path: Path) -> None:
    text = read_snapshot(path)
    print(f"\n=== {path.name} ===")
    for label, patterns in (
        ("send", SEND_PATTERNS),
        ("input", INPUT_PATTERNS),
        ("expert/deepthink", EXPERT_PATTERNS),
        ("chat/project", CHAT_PATTERNS),
    ):
        hints = find_hints(text, patterns)
        print(f"  {label}:")
        if not hints:
            print("    (none)")
            continue
        for h in hints:
            print(f"    - {h[:240]}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "prompt_dir",
        nargs="?",
        default=str(Path(__file__).resolve().parents[1] / "prompt"),
        help="Directory with HTML/MHTML snapshots (default: ../prompt)",
    )
    args = parser.parse_args(argv)
    prompt_dir = Path(args.prompt_dir)
    if not prompt_dir.is_dir():
        print(f"Not a directory: {prompt_dir}", file=sys.stderr)
        return 1
    files = sorted(list(prompt_dir.glob("*.html")) + list(prompt_dir.glob("*.mhtml")))
    if not files:
        print(f"No snapshots in {prompt_dir}", file=sys.stderr)
        return 1
    for f in files:
        analyze_file(f)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
