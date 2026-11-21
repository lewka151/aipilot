#!/usr/bin/env python3
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent / "prompt"
for name in [
    "Qwen Studio - Main Page.html",
    "DeepSeek - main page.mhtml",
    "Qwen Studio - project page.mhtml",
]:
    p = root / name
    if not p.exists():
        continue
    t = p.read_text(encoding="utf-8", errors="ignore")
    print("===", name, "===")
    patterns = [
        r"textarea[^>]{0,180}",
        r'contenteditable="true"[^>]{0,80}',
        r"chat-input[^>]{0,80}",
        r"data-message[^>]{0,80}",
        r"assistant[^>]{0,80}",
        r"ds-textarea__textarea[^>]{0,80}",
        r"ds-markdown[^>]{0,80}",
        r"sidebar-entry[^>]{0,120}",
        r'aria-label="[^"]{5,60}"',
        r'class="[^"]*project[^"]*"',
    ]
    seen = set()
    for pat in patterns:
        for m in re.finditer(pat, t, re.I):
            s = m.group(0)[:160]
            if s not in seen:
                seen.add(s)
                print(s)
    print()
