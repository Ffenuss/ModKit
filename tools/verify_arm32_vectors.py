#!/usr/bin/env python3
"""Independent execution of bytes emitted by the production ARM32 JNI encoder."""
import json
from pathlib import Path
import struct
import sys
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_LR, UC_ARM_REG_SP

results = []
for line in Path(sys.argv[1]).read_text().splitlines():
    kind, expected, code = line.split('\t')
    cpu = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    cpu.mem_map(0x10000, 0x10000)
    cpu.mem_map(0x30000, 0x1000)
    cpu.mem_write(0x10000, bytes.fromhex(code))
    cpu.mem_write(0x30000, b'\xa5' * 0x1000)
    cpu.reg_write(UC_ARM_REG_R0, 0x30000)
    cpu.reg_write(UC_ARM_REG_R1, 0x76543210)
    cpu.reg_write(UC_ARM_REG_R2, 0x12345678)
    cpu.reg_write(UC_ARM_REG_R3, 0x23456789)
    cpu.reg_write(UC_ARM_REG_SP, 0x30800)
    cpu.reg_write(UC_ARM_REG_LR, 0x18000)
    cpu.emu_start(0x10000, 0x18000, count=100)
    low, high = cpu.reg_read(UC_ARM_REG_R0), cpu.reg_read(UC_ARM_REG_R1)
    formats = {'Z': 'I', 'B': 'b', 'S': 'h', 'C': 'H', 'I': 'i', 'J': 'q', 'F': 'f', 'D': 'd'}
    fmt = formats[kind]
    raw = struct.pack('<II', low, high)
    value = struct.unpack('<' + fmt, raw[:struct.calcsize(fmt)])[0]
    assert value == (float(expected) if kind in ('F', 'D') else int(expected)), (kind, value, expected)
    assert bytes(cpu.mem_read(0x30000, 0x1000)) == b'\xa5' * 0x1000
    assert cpu.reg_read(UC_ARM_REG_R2) == 0x12345678
    assert cpu.reg_read(UC_ARM_REG_R3) == 0x23456789
    assert cpu.reg_read(UC_ARM_REG_SP) == 0x30800
    results.append({'kind': kind, 'value': value, 'state_unchanged': True})
assert len(results) == 10
output = {'engine': 'Unicorn ARM32', 'vectors': results}
Path(sys.argv[1]).with_suffix('.json').write_text(json.dumps(output, indent=2) + '\n')
print(json.dumps(output, indent=2))
