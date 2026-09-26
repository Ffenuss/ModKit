#!/usr/bin/env python3
"""Execute the Kotlin emitter's actual bytes in independent Unicorn ARM64 emulation.

Unicorn is a CI-only test tool and is not bundled with the Android application.
This is CPU-level verification, not a claim of Unity gameplay validation.
"""
import json
from pathlib import Path
import struct
import sys

from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X30, UC_ARM64_REG_S0, UC_ARM64_REG_D0,
    UC_ARM64_REG_CPACR_EL1,
)


def execute(code, kind, seed):
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(0x10000, 0x10000)
    machine.mem_map(0x30000, 0x1000)
    machine.mem_write(0x10000, bytes.fromhex(code))
    assert kind in ('i', 'f', 'd'), kind
    value = struct.pack('<' + dict(i='i', f='f', d='d')[kind], int(seed) if kind == 'i' else float(seed))
    machine.mem_write(0x30000 + (24 if kind == 'i' else 16), value)
    before = bytes(machine.mem_read(0x30000, 0x1000))
    machine.reg_write(UC_ARM64_REG_X0, 0x30000)
    machine.reg_write(UC_ARM64_REG_X30, 0x18000)
    machine.reg_write(UC_ARM64_REG_CPACR_EL1, 0x300000)
    machine.emu_start(0x10000, 0x18000, count=1000)
    assert bytes(machine.mem_read(0x30000, 0x1000)) == before, 'Unexpected state write'
    if kind == 'f':
        return struct.unpack('<f', struct.pack('<I', machine.reg_read(UC_ARM64_REG_S0)))[0]
    if kind == 'd':
        return struct.unpack('<d', struct.pack('<Q', machine.reg_read(UC_ARM64_REG_D0)))[0]
    return machine.reg_read(UC_ARM64_REG_X0)


vectors = Path(sys.argv[1])
results = []
for line in (line for path in sys.argv[1:] for line in Path(path).read_text().splitlines()):
    name, kind, seed, expected_before, expected_after, original, patched = line.split('\t')
    before = execute(original, kind, seed)
    after = execute(patched, kind, seed)
    assert before == float(expected_before), (name, before, expected_before)
    assert after == float(expected_after), (name, after, expected_after)
    results.append(dict(name=name, before=before, after=after, state_unchanged=True))
assert len(results) == (5 if len(sys.argv) == 3 else 3)
output = json.dumps({'engine': 'Unicorn 2.1.4 ARM64', 'results': results}, indent=2)
vectors.with_suffix('.json').write_text(output + '\n')
print(output)
