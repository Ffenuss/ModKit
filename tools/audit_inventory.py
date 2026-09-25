#!/usr/bin/env python3
"""Reproducible source inventory, not a claim of semantic or runtime coverage."""
import csv
import hashlib
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(["git", "ls-files"], cwd=ROOT, text=True).splitlines()
sources = [p for p in paths if p.endswith((".kt", ".java", ".c", ".xml", ".kts", ".yml", ".md"))]
rows = []
for name in sources:
    data = (ROOT / name).read_bytes()
    text = data.decode("utf-8")
    symbols = re.findall(r"^(?:data |sealed |enum |abstract |internal |private )*(?:class|object|interface) (\w+)", text, re.M)
    imports = re.findall(r"^import (io\.github\.ffenuss\.modkit\.[\w.]+)", text, re.M)
    rows.append([name, len(text.splitlines()), hashlib.sha256(data).hexdigest(), "; ".join(symbols), "; ".join(imports), len(re.findall(r"@Test\b", text))])
output = ROOT / "docs/audit/source-inventory.csv"
output.parent.mkdir(parents=True, exist_ok=True)
with output.open("w", newline="") as sink:
    writer = csv.writer(sink, lineterminator="\n")
    writer.writerow(["path", "lines", "sha256", "declarations", "internal_imports", "test_annotations"])
    writer.writerows(rows)
print(f"Inventoried {len(rows)} tracked text files; {sum(r[-1] for r in rows)} @Test annotations")
