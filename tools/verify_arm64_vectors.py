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
    UC_ARM64_REG_X0, UC_ARM64_REG_X2, UC_ARM64_REG_X30, UC_ARM64_REG_S0, UC_ARM64_REG_D0,
    UC_ARM64_REG_CPACR_EL1,
)


def execute(code, kind, seed, primitive_argument=None):
    machine = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    machine.mem_map(0x10000, 0x10000)
    machine.mem_map(0x30000, 0x1000)
    machine.mem_write(0x10000, bytes.fromhex(code))
    assert kind in ('b', 's', 'c', 'i', 'j', 'f', 'd'), kind
    value = struct.pack('<' + dict(b='b', s='h', c='H', i='i', j='q', f='f', d='d')[kind],
                        int(seed) if kind in ('b', 's', 'c', 'i', 'j') else float(seed))
    machine.mem_write(0x30000 + (24 if kind == 'i' else 16), value)
    before = bytes(machine.mem_read(0x30000, 0x1000))
    machine.reg_write(UC_ARM64_REG_X0, 0x30000)
    if primitive_argument is not None:
        machine.reg_write(UC_ARM64_REG_X2, int(primitive_argument))
    machine.reg_write(UC_ARM64_REG_X30, 0x18000)
    machine.reg_write(UC_ARM64_REG_CPACR_EL1, 0x300000)
    machine.emu_start(0x10000, 0x18000, count=1000)
    assert bytes(machine.mem_read(0x30000, 0x1000)) == before, 'Unexpected state write'
    if kind == 'f':
        return struct.unpack('<f', struct.pack('<I', machine.reg_read(UC_ARM64_REG_S0)))[0]
    if kind == 'd':
        return struct.unpack('<d', struct.pack('<Q', machine.reg_read(UC_ARM64_REG_D0)))[0]
    if kind in ('b', 's', 'c'):
        width = 1 if kind == 'b' else 2
        bits = machine.reg_read(UC_ARM64_REG_X0) & ((1 << (width * 8)) - 1)
        return struct.unpack('<' + dict(b='b', s='h', c='H')[kind], bits.to_bytes(width, 'little'))[0]
    return machine.reg_read(UC_ARM64_REG_X0)


vectors = Path(sys.argv[1])
results = []
for line in (line for path in sys.argv[1:] for line in Path(path).read_text().splitlines()):
    fields = line.split('\t')
    assert len(fields) in (7, 8)
    name, kind, seed, expected_before, expected_after, original, patched = fields[:7]
    argument = fields[7] if len(fields) == 8 else None
    before = execute(original, kind, seed, argument)
    after = execute(patched, kind, seed, argument)
    assert before == float(expected_before), (name, before, expected_before)
    assert after == float(expected_after), (name, after, expected_after)
    restored = execute(original, kind, seed, argument)
    assert restored == before, (name, restored, before)
    results.append(dict(name=name, before=before, after=after, state_unchanged=True))
expected_counts = {'native-verification.tsv': 3, 'native-catalog-verification.tsv': 2, 'native-jni-verification.tsv': 7, 'native-transform-verification.tsv': 12, 'native-computed-transform-verification.tsv': 24}
assert len(results) == sum(expected_counts[Path(path).name] for path in sys.argv[1:])
output = json.dumps({'engine': 'Unicorn 2.1.4 ARM64', 'results': results}, indent=2)
vectors.with_suffix('.json').write_text(output + '\n')
print(output)
