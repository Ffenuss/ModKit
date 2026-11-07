#!/usr/bin/env python3
"""Bounded ARM64 proof against the actual NDK-built game library in an APK.

The exact ELF symbol VA and file-offset are derived from .dynsym/.text.
Unicorn independently executes original, guarded patch and restored bytes.
This is CPU-level verification, NOT real-device Android live write-back.
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
from zipfile import ZipFile

from unicorn import UC_ARCH_ARM64, UC_MODE_ARM, Uc
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X30

LIBRARY = "lib/arm64-v8a/libmodkit_native_fixture.so"
SYMBOL = "native_fixture_step"
ORIGINAL = bytes.fromhex("40008052c0035fd6")  # mov w0,#2; ret
REPLACEMENT = bytes.fromhex("e0008052")          # mov w0,#7
MAX_LIBRARY_BYTES = 2_000_000
CODE_ADDR = 0x10000
EXIT_ADDR = 0x18000


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_from_apk(apk):
    with ZipFile(apk) as zipped:
        entry = zipped.getinfo(LIBRARY)
        require(64 <= entry.file_size <= MAX_LIBRARY_BYTES,
                "ARM64 fixture ELF is outside bounded size")
        data = zipped.read(entry)
    require(len(data) == entry.file_size, "Truncated native fixture ELF")
    return data


def find_fixture_body(elf):
    require(len(elf) >= 64 and elf[:6] == b"\\x7fELF\\x02\\x01",
            "Expected little-endian ELF64")
    require(struct.unpack_from("<H", elf, 16)[0] == 3,
            "Fixture must be a shared ELF object (ET_DYN)")
    require(struct.unpack_from("<H", elf, 18)[0] == 183,
            "Fixture is not AArch64 ELF (EM_AARCH64)")
    section_offset = struct.unpack_from("<Q", elf, 40)[0]
    section_entry_size = struct.unpack_from("<H", elf, 58)[0]
    section_count = struct.unpack_from("<H", elf, 60)[0]
    require(section_entry_size == 64 and 1 <= section_count <= 512,
            "Unexpected section table layout")
    require(64 <= section_offset <= len(elf) and
            section_count * section_entry_size <= len(elf) - section_offset,
            "Section headers exceed ELF length")
    sections = []
    for idx in range(section_count):
        item = struct.unpack_from(
            "<IIQQQQIIQQ", elf, section_offset + idx * section_entry_size)
        _, kind, flags, address, offset, size, link, _, _, entry_size = item
        require(offset <= len(elf) and size <= len(elf) - offset,
                "ELF section extends beyond file")
        sections.append(dict(
            kind=kind, flags=flags, address=address,
            offset=offset, size=size, link=link, entry_size=entry_size,
        ))
    dynsym_indices = [i for i, s in enumerate(sections) if s["kind"] == 11]
    require(len(dynsym_indices) == 1, "Require exactly one ELF .dynsym")
    dynsym = sections[dynsym_indices[0]]
    require(dynsym["entry_size"] == 24 and
            dynsym["size"] % 24 == 0 and
            dynsym["link"] < len(sections),
            "Malformed .dynsym table")
    strings = sections[dynsym["link"]]
    require(strings["kind"] == 3, ".dynsym link must point to string table")

    def read_name(offset):
        require(offset < strings["size"], "ELF symbol name out of range")
        start = strings["offset"] + offset
        end = elf.find(b"\\0", start, strings["offset"] + strings["size"])
        require(end != -1, "Unterminated ELF symbol")
        return elf[start:end].decode("utf-8")

    matches = []
    for idx in range(dynsym["size"] // dynsym["entry_size"]):
        at = dynsym["offset"] + idx * dynsym["entry_size"]
        name, info, _, shndx, value, size = struct.unpack_from("<IBBHQQ", elf, at)
        if read_name(name) == SYMBOL:
            matches.append((info, shndx, value, size))
    require(len(matches) == 1, "Require unique exported native fixture step")
    info, shndx, value, symbol_size = matches[0]
    require(info & 0x0F == 2, "Native fixture symbol must be STT_FUNC")
    require(0 < shndx < len(sections), "Undefined native fixture symbol")
    section = sections[shndx]
    require(section["flags"] & 0x4, "Function is not in executable section")
    require(value > 0 and value % 4 == 0, "Unaligned or missing ARM64 function")
    require(value >= section["address"] and
            value - section["address"] <= section["size"] - len(ORIGINAL),
            "Function body is outside executable section")
    if symbol_size:
        require(symbol_size >= len(ORIGINAL), "Truncated ARM64 function symbol")
    file_offset = section["offset"] + value - section["address"]
    body = elf[file_offset:file_offset + len(ORIGINAL)]
    require(body == ORIGINAL,
            "Compiled ARM64 bytes differ; refuse to manufacture a recipe")
    return value, file_offset, body


def guarded_patch(body, expected, replacement):
    require(len(expected) == len(replacement) == 4,
            "Native patch must be one 4-byte ARM64 instruction")
    require(body[:4] == expected, "Stale native expected bytes: refusing patch")
    return replacement + body[4:]


def execute(body):
    require(len(body) == len(ORIGINAL), "Unexpected ARM64 code length")
    cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    cpu.mem_map(CODE_ADDR, 0x10000)
    cpu.mem_write(CODE_ADDR, body)
    cpu.reg_write(UC_ARM64_REG_X30, EXIT_ADDR)
    cpu.emu_start(CODE_ADDR, EXIT_ADDR, count=32)
    return cpu.reg_read(UC_ARM64_REG_X0)


def verify(apk, destination):
    data = load_from_apk(apk)
    value, file_offset, original = find_fixture_body(data)
    assert execute(original) == 2, "NDK original did not return 2"
    patched = guarded_patch(original, original[:4], REPLACEMENT)
    assert patched[4:] == original[4:], "RET instruction was modified"
    assert execute(patched) == 7, "NDK patched function did not return 7"
    restored = guarded_patch(patched, REPLACEMENT, original[:4])
    assert restored == original
    assert execute(restored) == 2, "Restored function did not return 2"

    # Negative proof: exact-original-byte enforcement rejects stale recipes.
    rejected = False
    try:
        guarded_patch(patched, original[:4], REPLACEMENT)
    except ValueError:
        rejected = True
    assert rejected, "Stale expected machine bytes were incorrectly accepted"

    # The ELF on disk remains intact after CPU simulation.
    assert data[file_offset:file_offset + len(original)] == ORIGINAL
    report = {
        "level": "Unicorn ARM64 CPU semantics, not Android live runtime execution",
        "apk": Path(apk).name,
        "library": LIBRARY,
        "library_sha256": hashlib.sha256(data).hexdigest(),
        "symbol": SYMBOL,
        "virtual_address": hex(value),
        "file_offset": file_offset,
        "original_hex": original.hex(),
        "patch_expected_hex": original[:4].hex(),
        "patch_replacement_hex": REPLACEMENT.hex(),
        "results": {"original": 2, "on": 7, "off": 2},
        "stale_original_rejected": rejected,
        "restored_exactly": restored == original,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(
            "Usage: verify_arm64_fixture_elf.py <nativefixture-debug.apk> <report.json>")
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
