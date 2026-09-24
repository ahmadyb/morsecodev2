#!/usr/bin/env python3
"""
Static runtime-dependency check for a built APK (or an AAB's dex).

Finds classes the app *uses* that neither ship inside the dex nor exist in the platform. That is
exactly the failure mode that made the first artifacts useless: the offline build compiled the
Kotlin sources but never dexed the Kotlin standard library, so 101 calls into
`kotlin.collections.*`, `kotlin.Result` and `kotlin.jvm.internal.Intrinsics` were present in the
bytecode with no classes behind them - the app died with NoClassDefFoundError on launch while the
build reported success.

It is deliberately build-system independent (no Gradle, no d8 needed): it parses the dex directly.

Usage:
    python3 tools/dexcheck.py dist/MorseCode-1.0.1-release.apk
    python3 tools/dexcheck.py app.aab --android-jar $ANDROID_HOME/platforms/android-34/android.jar
Exit code 1 if anything is missing.
"""

import argparse
import os
import struct
import sys
import zipfile
from collections import Counter

# Namespaces the Android runtime always provides. Everything else has to be in the dex or in
# android.jar. `java.*`/`javax.*` come from the device's core libraries; the org.* entries are
# part of the Android framework (XML/JSON/HTTP) and show up in android.jar anyway.
PLATFORM_PREFIXES = (
    "Ljava/", "Ljavax/", "Ldalvik/", "Llibcore/", "Lsun/", "Lcom/sun/",
    "Lorg/json/", "Lorg/w3c/", "Lorg/xml/", "Lorg/apache/http/",
)

HEADER = {
    "string_ids": (0x38, 0x3C),
    "type_ids": (0x40, 0x44),
    "proto_ids": (0x48, 0x4C),
    "field_ids": (0x50, 0x54),
    "method_ids": (0x58, 0x5C),
    "class_defs": (0x60, 0x64),
}


class Dex:
    """Just enough of the dex format: string/type tables and class definitions."""

    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise ValueError("not a dex file")
        self.data = data
        self._tables = {}
        for name, (size_off, off_off) in HEADER.items():
            self._tables[name] = (read_u32(data, size_off), read_u32(data, off_off))

    def string(self, index):
        size, off = self._tables["string_ids"]
        if index >= size:
            raise IndexError("string index out of range")
        offset = read_u32(self.data, off + 4 * index)
        length, pos = read_uleb128(self.data, offset)
        return self.data[pos:pos + length].decode("utf-8", "replace")

    def type(self, index):
        size, off = self._tables["type_ids"]
        if index >= size:
            raise IndexError("type index out of range")
        return self.string(read_u32(self.data, off + 4 * index))

    def all_types(self):
        size, _ = self._tables["type_ids"]
        return (self.type(i) for i in range(size))

    def defined_classes(self):
        size, off = self._tables["class_defs"]
        return (self.type(read_u32(self.data, off + 32 * i)) for i in range(size))


def read_u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def read_uleb128(data, offset):
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, offset


def is_real_class(descriptor):
    """Skip primitives (I, V, ...) and arrays ([L...;)."""
    return descriptor.startswith("L") and descriptor.endswith(";")


def platform_classes(android_jar):
    """Class names provided by android.jar, as dex descriptors."""
    if not android_jar or not os.path.exists(android_jar):
        return set()
    out = set()
    with zipfile.ZipFile(android_jar) as z:
        for name in z.namelist():
            if name.endswith(".class"):
                out.add("L" + name[:-6] + ";")
    return out


def dex_blobs(archive):
    """Every classes*.dex inside an APK or an AAB, as (name, bytes)."""
    blobs = []
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if name.endswith(".dex") and name.startswith(("classes", "base/dex/")):
                blobs.append((name, z.read(name)))
    return blobs


def check(archive, android_jar=None):
    """Returns (defined_count, missing: Counter[descriptor] -> references)."""
    platform = platform_classes(android_jar)
    defined = set()
    referenced = Counter()
    for name, blob in dex_blobs(archive):
        dex = Dex(blob)
        for descriptor in dex.defined_classes():
            defined.add(descriptor)
        for descriptor in dex.all_types():
            if is_real_class(descriptor):
                referenced[descriptor] += 1
    missing = Counter()
    for descriptor, count in referenced.items():
        if descriptor in defined or descriptor in platform:
            continue
        if descriptor.startswith(PLATFORM_PREFIXES):
            continue
        missing[descriptor] = count
    return len(defined), missing


def summarise(missing, limit=25):
    """Group by package so one missing library reads as one problem."""
    groups = Counter()
    for descriptor, count in missing.items():
        package = descriptor[1:].rsplit("/", 1)[0] if "/" in descriptor else descriptor
        groups[package] += count
    return groups.most_common(limit)


def main():
    ap = argparse.ArgumentParser(description="check an APK/AAB for missing runtime classes")
    ap.add_argument("archive", help="APK or AAB to inspect")
    ap.add_argument("--android-jar", default=os.environ.get("ANDROID_JAR"),
                    help="platform android.jar to treat as provided")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    android_jar = args.android_jar
    if not android_jar:
        for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
            if not root:
                continue
            for api in ("34", "35", "33", "36"):
                candidate = os.path.join(root, "platforms", "android-%s" % api, "android.jar")
                if os.path.exists(candidate):
                    android_jar = candidate
                    break
            if android_jar:
                break

    defined, missing = check(args.archive, android_jar)
    if not args.quiet:
        print("%s: %d classes defined, %d distinct classes referenced"
              % (os.path.basename(args.archive), defined, len(set(missing)) or 0))
        if android_jar:
            print("   platform: %s" % android_jar)
        else:
            print("   platform: (no android.jar found - only java.*/javax.* treated as provided)")
    if not missing:
        print("   ok: every referenced class is either bundled or part of the platform")
        return 0

    print("   MISSING %d class(es) - these will throw NoClassDefFoundError at runtime:"
          % len(missing))
    for package, count in summarise(missing):
        examples = ", ".join(d[1:-1] for d, _ in
                             [(d, c) for d, c in missing.most_common()
                              if d[1:].rsplit("/", 1)[0] == package][:3])
        print("      %-46s %2d reference(s)  e.g. %s" % (package, count, examples))
    return 1


if __name__ == "__main__":
    sys.exit(main())
