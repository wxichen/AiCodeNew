#!/usr/bin/env python3
"""把预编译 .so 的 PT_LOAD 段对齐改成 16KB 页大小。

仓库里没有 Termux 的 C 源码，libtermux.so 只能就地改 program header 的 p_align。
改的字节仅限两个 LOAD 段的 Align 字段（4KB → 16KB），文件布局与内容布局不变。

用法：python3 scripts/fix-native-align.py <file.so> [...]
"""

import struct
import sys
from pathlib import Path

REQUIRED_ALIGN = 0x4000


def fix(path: Path) -> int:
    data = bytearray(path.read_bytes())
    if data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")
    if data[5] != 1:
        raise ValueError("只支持小端 ELF")
    if data[4] == 2:
        phoff, = struct.unpack_from("<Q", data, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
        align_at, fmt = 0x30, "<Q"
    elif data[4] == 1:
        phoff, = struct.unpack_from("<I", data, 0x1C)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x2A)
        align_at, fmt = 0x1C, "<I"
    else:
        raise ValueError("未知 ELF 位宽")

    changed = 0
    for i in range(phnum):
        base = phoff + i * phentsize
        ptype, = struct.unpack_from("<I", data, base)
        if ptype != 1:
            continue
        current, = struct.unpack_from(fmt, data, base + align_at)
        if current < REQUIRED_ALIGN:
            struct.pack_into(fmt, data, base + align_at, REQUIRED_ALIGN)
            changed += 1
    if changed:
        path.write_bytes(data)
    return changed


def main(argv):
    if not argv:
        print("用法：python3 scripts/fix-native-align.py <file.so> [...]")
        return 2
    for arg in argv:
        path = Path(arg)
        try:
            n = fix(path)
        except ValueError as exc:
            print(f"[FAIL] {path}: {exc}")
            return 1
        print(f"{'已修改 %d 个 LOAD 段' % n if n else '已对齐，未改动'}  {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
