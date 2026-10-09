#!/usr/bin/env python3
"""Run bytes emitted by the production x86_64 JNI encoder in independent Unicorn."""
import json
from pathlib import Path
import struct
import sys
from unicorn import Uc, UC_ARCH_X86, UC_MODE_64
from unicorn.x86_const import (UC_X86_REG_RAX, UC_X86_REG_RBX, UC_X86_REG_RBP,
    UC_X86_REG_R12, UC_X86_REG_R13, UC_X86_REG_R14, UC_X86_REG_R15,
    UC_X86_REG_RSP, UC_X86_REG_RDX, UC_X86_REG_XMM0)

results = []
for line in Path(sys.argv[1]).read_text().splitlines():
    kind, expected, code = line.split('\t')
    cpu = Uc(UC_ARCH_X86, UC_MODE_64)
    cpu.mem_map(0x10000, 0x10000)
    cpu.mem_map(0x30000, 0x1000)
    cpu.mem_write(0x10000, bytes.fromhex(code))
    cpu.mem_write(0x30000, b'\xa5' * 0x1000)
    cpu.mem_write(0x30800, struct.pack('<Q', 0x18000))
    before = bytes(cpu.mem_read(0x30000, 0x1000))
    cpu.reg_write(UC_X86_REG_RSP, 0x30800)
    cpu.reg_write(UC_X86_REG_RDX, 0x100000002)
    saved = [UC_X86_REG_RBX, UC_X86_REG_RBP, UC_X86_REG_R12, UC_X86_REG_R13, UC_X86_REG_R14, UC_X86_REG_R15]
    for i, reg in enumerate(saved): cpu.reg_write(reg, 0xabc000000000 + i)
    cpu.emu_start(0x10000, 0x18000, count=100)
    value = cpu.reg_read(UC_X86_REG_XMM0 if kind in ('F', 'D') else UC_X86_REG_RAX)
    fmt = {'Z': 'I', 'B': 'b', 'S': 'h', 'C': 'H', 'I': 'i', 'J': 'q', 'F': 'f', 'D': 'd'}[kind]
    raw = (value & ((1 << 64) - 1)).to_bytes(8, 'little')
    result = struct.unpack('<' + fmt, raw[:struct.calcsize(fmt)])[0]
    assert result == (float(expected) if kind in ('F', 'D') else int(expected)), (kind, result, expected)
    assert cpu.reg_read(UC_X86_REG_RSP) == 0x30808
    assert cpu.reg_read(UC_X86_REG_RDX) == 0x100000002
    assert bytes(cpu.mem_read(0x30000, 0x1000)) == before
    for i, reg in enumerate(saved): assert cpu.reg_read(reg) == 0xabc000000000 + i
    results.append({'kind': kind, 'result': result, 'state_unchanged': True})
assert len(results) == 10
report = {'engine': 'Unicorn x86_64', 'vectors': results}
Path(sys.argv[1]).with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
