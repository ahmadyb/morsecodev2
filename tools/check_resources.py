#!/usr/bin/env python3
"""
Compiles and links the app's resources with aapt2 alone.

Kotlin is not involved, so this runs in a few seconds anywhere aapt2 and an android.jar exist -
which makes it the fast gate for resource work (vector drawables, colours, styles, the manifest).
It exists because `aapt2 compile` on its own accepts things `aapt2 link` rejects: a colour like
`#8C2A1701` passes compile and fails link, and only the Gradle path used to catch it.

Usage:  python3 tools/check_resources.py [--keep]
Exit code is non-zero when a resource does not link.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import offline_build  # noqa: E402  (reuses the tool discovery and manifest staging)


def main():
    ap = argparse.ArgumentParser(description="aapt2 compile + link check for app/src/main/res")
    ap.add_argument("--build-tools")
    ap.add_argument("--platforms")
    ap.add_argument("--aapt2")
    ap.add_argument("--api", default="34", help="platform to link against (default 34)")
    ap.add_argument("--keep", action="store_true", help="keep the linked test APK")
    args = ap.parse_args()

    # Only aapt2, a platform jar and a JDK are needed - no Kotlin compiler, which is what lets
    # this run as a fast first step in CI and in sandboxes that cannot fetch kotlinc.
    t = offline_build.tools(argparse.Namespace(
        jdk=None, build_tools=args.build_tools, platforms=args.platforms,
        kotlinc=None, aapt2=args.aapt2), need_kotlinc=False)
    aapt2 = t["aapt2"]
    android_jar = os.path.join(t["platforms"], "android-%s" % args.api, "android.jar")
    if not os.path.exists(android_jar):
        # the platforms directory may itself hold the version folders (SDK layout)
        alt = os.path.join(t["platforms"], "android.jar")
        android_jar = alt if os.path.exists(alt) else android_jar
    if not os.path.exists(android_jar):
        sys.exit("error: no android-%s/android.jar under %s" % (args.api, t["platforms"]))

    work = tempfile.mkdtemp(prefix="mc-res-")
    try:
        manifest = offline_build.staged_manifest()
        res_zip = os.path.join(work, "res.zip")
        run([aapt2, "compile", "--dir", os.path.join(ROOT, "app", "src", "main", "res"),
             "-o", res_zip], "compile")
        out_apk = os.path.join(work, "resources.apk")
        run([aapt2, "link", "-I", android_jar, "--manifest", manifest, "-o", out_apk,
             "--min-sdk-version", "21", "--target-sdk-version", "34", "--no-version-vectors",
             res_zip], "link")
        print("resources ok: %s -> %d bytes" % (os.path.basename(out_apk), os.path.getsize(out_apk)))
        if args.keep:
            dest = os.path.join(ROOT, "resources-check.apk")
            shutil.copy(out_apk, dest)
            print("kept", dest)
    finally:
        if not args.keep:
            shutil.rmtree(work, ignore_errors=True)


def run(cmd, what):
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout + proc.stderr)
        sys.exit("error: aapt2 %s failed" % what)
    if proc.stderr.strip():
        print(proc.stderr.strip())


if __name__ == "__main__":
    main()
