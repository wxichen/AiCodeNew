#!/usr/bin/env python3
"""检查随 APK 打包的预编译 .so 是否满足 16KB 页大小对齐。

Android 15+ 在部分设备上使用 16KB 内存页；此时 ELF 的 PT_LOAD 段对齐小于 16KB
会导致 dlopen 失败。Termux 的 libtermux.so 是预编译产物（仓库里没有 C 源码），
只能直接改 program header 的 p_align，因此需要这个校验防止以后换文件时回退。

用法：python3 scripts/check-native-align.py [额外文件...]
不传参数时检查仓库内固定清单；退出码非 0 表示有文件不达标。
"""

import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REQUIRED_ALIGN = 0x4000

# 仓库内随包发布的预编译产物。ABI flavor 目录下还有 proot 全套，但 32 位 loader 是
# 独立可执行文件（不经 dlopen），按 4KB 对齐属正常，故不列入。
TRACKED = [
    "terminal-emulator/src/main/jniLibs/arm64-v8a/libtermux.so",
    "terminal-emulator/src/main/jniLibs/armeabi-v7a/libtermux.so",
    "terminal-emulator/src/main/jniLibs/x86/libtermux.so",
    "terminal-emulator/src/main/jniLibs/x86_64/libtermux.so",
    "app/src/_armJniLibs/arm64-v8a/libproot.so",
    "app/src/_armJniLibs/arm64-v8a/libproot-loader.so",
    "app/src/_armJniLibs/arm64-v8a/libtalloc.so",
    "app/src/_armJniLibs/arm64-v8a/libandroid-shmem.so",
    "app/src/_x86JniLibs/x86_64/libproot.so",
    "app/src/_x86JniLibs/x86_64/libproot-loader.so",
    "app/src/_x86JniLibs/x86_64/libtalloc.so",
    "app/src/_x86JniLibs/x86_64/libandroid-shmem.so",
]


def load_alignments(path: Path):
    data = path.read_bytes()
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
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        ptype, = struct.unpack_from("<I", data, base)
        if ptype == 1:
            aligns.append(struct.unpack_from(fmt, data, base + align_at)[0])
    return aligns


def main(argv):
    targets = [Path(a) for a in argv] if argv else [ROOT / p for p in TRACKED]
    bad = []
    for path in targets:
        if not path.is_file():
            bad.append((path, "文件不存在"))
            continue
        try:
            aligns = load_alignments(path)
        except ValueError as exc:
            bad.append((path, str(exc)))
            continue
        low = [a for a in aligns if a < REQUIRED_ALIGN]
        if low:
            bad.append((path, "LOAD 段对齐 " + ", ".join(f"{a:#x}" for a in low)))
    if bad:
        print("[FAIL] 以下预编译 .so 不满足 16KB 页大小对齐：")
        for path, reason in bad:
            print(f"  - {path.relative_to(ROOT) if path.is_absolute() else path}: {reason}")
        print("修复：python3 scripts/fix-native-align.py <文件>")
        return 1
    print(f"[OK] {len(targets)} 个预编译 .so 均按 16KB 页大小对齐")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
