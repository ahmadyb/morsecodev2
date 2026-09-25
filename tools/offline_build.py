#!/usr/bin/env python3
"""
MorseCode - offline APK / AAB builder (Gradle-free).

Why this exists
---------------
This repository builds without Gradle or Android Studio on machines where the
Google Maven repository / Maven Central are not reachable.  The whole chain is
made of small, replaceable command line tools:

    aapt2        resource compilation + linking        (tools/aapt2)
    kotlinc      Kotlin -> JVM .class                  (tools/kotlinc)
    dx (dx.jar)  JVM .class -> classes.dex             (build-tools/lib/dx.jar)
    zipalign     APK alignment                         (build-tools/zipalign)
    apksigner    v1 + v2 + v3 APK signing              (build-tools/lib/apksigner.jar)
    keytool      keystore creation                     (JDK)

Use `python3 tools/offline_build.py --help` for options.
The layout produced here is byte-for-byte the same structure Gradle would
produce: `app/src/main/java`, `app/src/main/res`, `app/src/main/assets`.

Outputs (dist/):
    MorseCode-1.0.0-release.apk      (signed, v1+v2 verified)
    MorseCode-1.0.0-debug.apk        (signed with the debug key)
    MorseCode-1.0.0.aab              (Android App Bundle, proto-format resources)
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOTLINC_PATH = "kotlinc"

# What a failure looks like, in the output of every tool this build drives.
ERR_MARKERS = __import__("re").compile(
    r"(^|\s)(error|ERROR|Error):|error:|FAILED|FAILURE|Exception|Caused by|Unresolved|"
    r"cannot find symbol|could not|No such file",
)
APP = os.path.join(ROOT, "app")
MAIN = os.path.join(APP, "src", "main")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")

APP_ID = "com.morsecode.app"
# Same contract as the Gradle build: CI exports these from the release tag (v1.2.3 -> 1.2.3).
VERSION_NAME = os.environ.get("MC_VERSION_NAME", "1.0.0")
VERSION_CODE = os.environ.get("MC_VERSION_CODE", "1")
MIN_SDK = "21"
TARGET_SDK = "34"

# --------------------------------------------------------------------------
# tool discovery
# --------------------------------------------------------------------------


def find_tool(explicit, env_names, candidates, what):
    if explicit:
        if os.path.exists(explicit):
            return explicit
        sys.exit("error: %s not found at %s" % (what, explicit))
    for name in env_names:
        v = os.environ.get(name)
        if v and os.path.exists(v):
            return v
    for c in candidates:
        if os.path.exists(c):
            return c
    sys.exit(
        "error: %s not found.\n"
        "Set the matching environment variable or place the tool in tools/.\n"
        "Looked in: %s" % (what, ", ".join(candidates))
    )


def sdk_roots():
    """Every directory that might be an Android SDK root."""
    for root in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 "/tmp/tools/asdk", "/opt/android-sdk", os.path.expanduser("~/Android/Sdk")):
        if root and os.path.isdir(root):
            yield root


def build_tools_candidates():
    """
    Versioned build-tools directories, newest first, under any SDK root.

    This is what a GitHub runner leaves behind after the shell `sdkmanager` step, so the offline
    fallback uses build-tools 34 (d8) instead of failing with "no build-tools found".
    """
    out = []
    for root in sdk_roots():
        base = os.path.join(root, "build-tools")
        if os.path.isdir(base):
            out.extend(sorted((os.path.join(base, d) for d in os.listdir(base)), reverse=True))
    pinned = os.environ.get("ANDROID_BUILD_TOOLS")
    if pinned:
        for root in sdk_roots():
            candidate = os.path.join(root, "build-tools", pinned)
            if os.path.isdir(candidate):
                out.insert(0, candidate)
    return out


def platform_candidates():
    """
    Directories *containing* one folder per platform, e.g. `$ANDROID_HOME/platforms`.

    Note the asymmetry with build-tools: the rest of the builder joins
    `<platforms>/android-23/android.jar`, so this must return the parent, not a version folder.
    """
    return [os.path.join(root, "platforms") for root in sdk_roots()
            if os.path.isdir(os.path.join(root, "platforms"))]


def tools(args, need_kotlinc=True):
    jdk = find_tool(
        args.jdk,
        ["JAVA_HOME"],
        [
            "/tmp/tools/jdk/jdk4py/java-runtime",
            os.path.join(ROOT, "tools", "jdk"),
        ],
        "JDK (java)",
    )
    java = os.path.join(jdk, "bin", "java")
    bt = find_tool(
        args.build_tools,
        ["ANDROID_BUILD_TOOLS"],
        build_tools_candidates()
        + [
            "/tmp/tools/asdk/build-tools/26.0.2",
            os.path.join(ROOT, "tools", "build-tools"),
        ],
        "Android build-tools",
    )
    platforms = find_tool(
        args.platforms,
        ["ANDROID_PLATFORMS"],
        platform_candidates()
        + [
            "/tmp/tools/apl",
            os.path.join(ROOT, "tools", "platforms"),
        ],
        "Android platforms (android.jar)",
    )
    # Resource-only work (tools/check_resources.py) does not need the Kotlin compiler at all.
    kotlinc = None
    if need_kotlinc:
        kotlinc = find_tool(
            args.kotlinc,
            # The workflow exports KOTLINC; its fallback step used to export KOTLINC_PATH, which
            # nothing read - so a Gradle build that fell back to this toolchain could never find
            # the compiler it had just downloaded.
            ["KOTLINC", "KOTLINC_PATH"],
            [
                "/tmp/kotlinc/bin/kotlinc",
                "/tmp/pkgs/kc/package/bin/kotlinc",
                os.path.join(ROOT, "tools", "kotlinc", "bin", "kotlinc"),
            ],
            "kotlinc",
        )
    aapt2 = find_tool(
        args.aapt2,
        ["AAPT2"],
        [
            # the copy that ships inside the build-tools we already located (this is the normal
            # case on a CI runner: $ANDROID_HOME/build-tools/<ver>/aapt2)
            os.path.join(bt, "aapt2"),
            os.path.join(bt, "aapt2.exe"),
            "/tmp/pkgs/aapt/package/bin/x64/linux/aapt2",
            os.path.join(ROOT, "tools", "aapt2"),
        ],
        "aapt2",
    )
    if kotlinc:
        globals()["KOTLINC_PATH"] = kotlinc
    return {
        "jdk": jdk,
        "java": java,
        "bin": os.path.join(jdk, "bin"),
        "build_tools": bt,
        "platforms": platforms,
        "kotlinc": kotlinc,
        "aapt2": aapt2,
    }


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------


class Sh:
    def __init__(self, env):
        self.env = env

    def run(self, cmd, cwd=None, quiet=False):
        if not quiet:
            print("  $", " ".join(os.path.basename(str(c)) for c in cmd[:2]), "...")
        p = subprocess.run(
            cmd,
            cwd=cwd,
            env=self.env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        if p.returncode != 0:
            print(p.stdout)
            # Compilers and dexers bury the actual cause under a stack trace, and this build
            # reports its failures through CI annotations that only keep the tail of the log -
            # so repeat the head of the error last, where it survives.
            lines = [ln for ln in (p.stdout or "").splitlines() if ln.strip()]
            # The lines that actually say what is wrong come first: a compiler error, a Kotlin
            # "error:", an exception. They are usually in the middle of the output, and the
            # annotation channel keeps only the end of it.
            bad = [ln for ln in lines if ERR_MARKERS.search(ln) and "FAILURE-" not in ln]
            if bad:
                print("FAILURE-ERROR-LINES:")
                for line in bad[:30]:
                    print("    " + line)
            print("FAILURE-FIRST-LINES:")
            for line in lines[:25]:
                print("    " + line)
            print("FAILURE-LAST-LINES:")
            for line in lines[-10:]:
                print("    " + line)
            sys.exit("error: command failed with %d: %s" % (p.returncode, " ".join(map(str, cmd))))
        if not quiet and p.stdout.strip():
            for line in p.stdout.strip().splitlines()[-6:]:
                print("    " + line)
        return p.stdout


def check_env(T, extra_path=()):
    env = dict(os.environ)
    env["JAVA_HOME"] = T["jdk"]
    env["PATH"] = os.pathsep.join([T["bin"]] + list(extra_path) + [env.get("PATH", "")])
    env["LANG"] = "C.UTF-8"
    env["LC_ALL"] = "C.UTF-8"
    return env


# --------------------------------------------------------------------------
# build stages
# --------------------------------------------------------------------------


def staged_manifest():
    """
    aapt2 insists on a `package` attribute in the manifest; AGP 8 forbids it in the source.
    The source manifest stays AGP-clean and the attribute is injected for this build only.
    """
    src = os.path.join(MAIN, "AndroidManifest.xml")
    with open(src, "r", encoding="utf-8") as fh:
        text = fh.read()
    if 'package="' in text.split(">", 1)[0]:
        return src
    marker = "<manifest"
    idx = text.index(marker)
    end = text.index(">", idx)
    declared = text[idx:end].rstrip()
    patched = text[:idx] + declared + '\n    package="%s"' % APP_ID + text[end:]
    # AGP resolves ${applicationId} during manifest merging; the offline link does it here, so a
    # provider authority (${applicationId}.share) matches the package this build installs as.
    patched = patched.replace("${applicationId}", APP_ID)
    out = os.path.join(BUILD, "AndroidManifest.xml")
    os.makedirs(BUILD, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(patched)
    return out


def stage_resources(sh, T, proto):
    """aapt2 compile + link.  Returns (apk_path, r_txt_path)."""
    res_zip = os.path.join(BUILD, "resources.zip")
    if os.path.exists(res_zip):
        os.remove(res_zip)
    sh.run([T["aapt2"], "compile", "--dir", os.path.join(MAIN, "res"), "-o", res_zip])
    out = os.path.join(BUILD, "base-proto.apk" if proto else "base.apk")
    cmd = [
        T["aapt2"],
        "link",
        "-o",
        out,
        "-I",
        os.path.join(T["platforms"], "android-34", "android.jar"),
        "--manifest",
        staged_manifest(),
        "-R",
        res_zip,
        "--output-text-symbols",
        os.path.join(BUILD, "R.txt"),
        "--min-sdk-version",
        MIN_SDK,
        "--target-sdk-version",
        TARGET_SDK,
        "--version-code",
        VERSION_CODE,
        "--version-name",
        VERSION_NAME,
        "--auto-add-overlay",
        "--no-static-lib-packages",
    ]
    if proto:
        cmd.append("--proto-format")
    sh.run(cmd)
    return out, os.path.join(BUILD, "R.txt")


KOTLIN_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch",
    "constructor", "delegate", "dynamic", "field", "file", "finally", "get", "import",
    "init", "param", "property", "receiver", "set", "setparam", "where", "abstract",
    "annotation", "companion", "const", "crossinline", "data", "enum", "external",
    "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open",
    "operator", "override", "private", "protected", "public", "reified", "sealed",
    "suspend", "tailrec", "vararg", "out",
}


def gen_r_kt(r_txt, out_path):
    """Turn aapt2's R.txt into a Kotlin `R` object (no javac needed)."""
    groups = {}
    with open(r_txt) as fh:
        for line in fh:
            p = line.split()
            if len(p) < 4 or p[0] != "int":
                continue
            groups.setdefault(p[1], []).append((p[2], p[3]))
    out = ["package %s" % APP_ID, "", "/** Generated by tools/offline_build.py - do not edit. */", "object R {"]
    for t in sorted(groups):
        out.append("    object %s {" % t)
        for name, value in sorted(groups[t]):
            n = name if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", name) and name not in KOTLIN_KEYWORDS else "`%s`" % name
            out.append("        const val %s: Int = %s" % (n, value))
        out.append("    }")
    out.append("}")
    with open(out_path, "w") as fh:
        fh.write("\n".join(out) + "\n")
    return len(groups)


def kotlin_sources():
    src_root = os.path.join(MAIN, "java")
    compat, main = [], []
    for dirpath, _dirnames, filenames in os.walk(src_root):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            full = os.path.join(dirpath, fn)
            if os.sep + "compat" + os.sep in full:
                compat.append(full)
            else:
                main.append(full)
    return sorted(compat), sorted(main)


def jvm_default_flag():
    """
    Names the switch that pins interface bodies to DefaultImpls.

    Kotlin 2.2 folded `-Xjvm-default` into `-jvm-default`; older compilers only understand the
    experimental spelling, so the flag is chosen from the compiler's own version string.
    """
    text = ""
    try:
        p = subprocess.run(
            [KOTLINC_PATH, "-version"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        text = p.stdout or ""
    except Exception:
        text = ""
    m = re.search(r"kotlinc[^\s]*\s+(\d+)\.(\d+)(?:\.(\d+))?", text)
    if m:
        major, minor = int(m.group(1)), int(m.group(2))
        if (major, minor) >= (2, 2):
            return "-jvm-default=disable"
    return "-Xjvm-default=disable"


def kotlin_runtime_jars(T):
    """
    The Kotlin standard library that ships with kotlinc, plus the annotations jar next to it.

    These have to be dexed into the APK: the compiler emits real calls into `kotlin.collections.*`,
    `kotlin.Result` and `kotlin.jvm.internal.Intrinsics`, so a build that compiles the sources but
    leaves the runtime out produces an APK that dies with NoClassDefFoundError on launch - which is
    exactly what the first artifacts did. tools/dexcheck.py now fails the build if it ever happens
    again.
    """
    lib = os.path.join(os.path.dirname(os.path.dirname(T["kotlinc"])), "lib")
    jars = []
    for name in ("kotlin-stdlib.jar", "kotlin-stdlib-common.jar", "annotations-13.0.jar"):
        candidate = os.path.join(lib, name)
        if os.path.exists(candidate):
            jars.append(candidate)
    return jars


def stage_kotlin(sh, T, r_kt, jobs):
    """Compile compat sources (API 34) then everything else (API 23 - compile-time
    enforcement that the app only uses APIs that exist on Android 6)."""
    compat, main = kotlin_sources()
    out_compat = os.path.join(BUILD, "classes-compat")
    out_main = os.path.join(BUILD, "classes-main")
    for d in (out_compat, out_main):
        shutil.rmtree(d, ignore_errors=True)

    jar34 = os.path.join(T["platforms"], "android-34", "android.jar")
    jar23 = os.path.join(T["platforms"], "android-23", "android.jar")
    # interface bodies must stay in DefaultImpls: dx/d8 cannot translate real default or static
    # interface methods at minSdk 21, and API 21-23 devices could not run them anyway.
    kotlinc = [T["kotlinc"], "-jvm-target", "1.8", jvm_default_flag(),
               "-Xlambdas=class", "-Xsam-conversions=class", "-nowarn"]
    if compat:
        # R lives only in the main pass, otherwise both outputs would define com.morsecode.app.R
        sh.run(kotlinc + ["-classpath", jar34, "-d", out_compat] + compat, quiet=False)
    cp = jar23 + os.pathsep + out_compat
    args = kotlinc + ["-classpath", cp, "-d", out_main] + main + [r_kt]
    sh.run(args)
    print("    compiled %d main + %d compat Kotlin files" % (len(main), len(compat)))
    return [out_compat, out_main] if compat else [out_main]


def sanitise_jar(path, dest_dir):
    """
    Copy a jar without the entries a dexer cannot read.

    kotlin-stdlib is a multi-release jar: it carries `META-INF/versions/9/module-info.class`
    (class file version 53) plus its own `module-info.class`. d8 aborts with
    "Compilation failed to complete" on those, while dx quietly ignored them - which is exactly
    the difference between the sandbox build and a CI runner. Everything else is copied verbatim.
    """
    name = os.path.basename(path)
    os.makedirs(dest_dir, exist_ok=True)
    out = os.path.join(dest_dir, name)
    with zipfile.ZipFile(path) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        kept = 0
        for item in src.infolist():
            entry = item.filename
            if entry.startswith("META-INF/versions/") or entry.endswith("module-info.class"):
                continue
            dst.writestr(item, src.read(entry))
            kept += 1
    return out, kept


def zip_classes(class_dirs, out_jar):
    """
    Pack compiled class directories into one archive.

    dx accepted a directory as input; d8 does not - it fails with "Unsupported source file type"
    on `build/classes-main`. A zip of the same classes is accepted by both.
    """
    count = 0
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        for base in class_dirs:
            for dirpath, _dirnames, filenames in os.walk(base):
                for fn in filenames:
                    full = os.path.join(dirpath, fn)
                    zf.write(full, os.path.relpath(full, base))
                    count += 1
    return out_jar, count


def stage_dex(sh, T, class_dirs):
    """Dex the app plus the Kotlin runtime; returns [classes.dex, classes2.dex, ...]."""
    out_dir = os.path.join(BUILD, "dex")
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir, exist_ok=True)
    jars = kotlin_runtime_jars(T)
    if not jars:
        sys.exit("error: no kotlin-stdlib.jar next to %s - the APK would be missing the Kotlin "
                 "runtime (see kotlin_runtime_jars)" % T["kotlinc"])
    jar_dir = os.path.join(BUILD, "runtime-jars")
    shutil.rmtree(jar_dir, ignore_errors=True)
    os.makedirs(jar_dir, exist_ok=True)
    runtime = []
    for jar in jars:
        clean, kept = sanitise_jar(jar, jar_dir)
        print("    dexing %s (%d entries)" % (os.path.basename(jar), kept))
        runtime.append(clean)
    app_jar, classes = zip_classes(class_dirs, os.path.join(BUILD, "classes-app.jar"))
    print("    %d compiled class files" % classes)
    sh.run(
        dexer_cmd(T, out_dir) + [app_jar] + runtime,
        quiet=True,
    )
    dexs = sorted(
        (os.path.join(out_dir, f) for f in os.listdir(out_dir) if f.endswith(".dex")),
        key=lambda p: (len(os.path.basename(p)), os.path.basename(p)),
    )
    if not dexs or os.path.basename(dexs[0]) != "classes.dex":
        sys.exit("error: the dexer produced no classes.dex in %s (got %s)"
                 % (out_dir, os.listdir(out_dir)))
    print("    %d dex file(s): %s" % (len(dexs), ", ".join(os.path.basename(d) for d in dexs)))
    return dexs


def dexer_cmd(T, out_dir):
    """
    Picks a dexer from the build-tools the machine actually has and writes into `out_dir`.

    build-tools <= 30 ship dx.jar; newer ones ship d8 (a D8 shell script plus d8.jar). Two traps,
    both hit in CI:

      * d8 refuses a `.dex` filename as its output ("Output must be a .zip or .jar archive or an
        existing directory"); dx accepts one. Asking both for a directory output sidesteps it.
      * the app plus the Kotlin runtime is more than one dex' worth of methods, so multidex has to
        be on. API 21+ loads classes2.dex natively, which is exactly our minSdk.
    """
    requested = os.environ.get("MC_DEXER", "").lower()
    dx_jar = os.path.join(T["build_tools"], "lib", "dx.jar")
    d8_jar = os.path.join(T["build_tools"], "lib", "d8.jar")
    d8_script = os.path.join(T["build_tools"], "d8")

    if requested == "dx" or (not requested and os.path.exists(dx_jar)):
        if not os.path.exists(dx_jar):
            sys.exit("error: MC_DEXER=dx but %s does not exist" % dx_jar)
        return [
            T["java"], "-cp", dx_jar, "com.android.dx.command.Main",
            "--dex", "--multi-dex", "--min-sdk-version=" + MIN_SDK, "--output=" + out_dir,
        ]
    if os.path.exists(d8_jar):
        return [
            T["java"], "-cp", d8_jar, "com.android.tools.r8.D8",
            "--min-api", MIN_SDK,
            "--lib", os.path.join(T["platforms"], "android-34", "android.jar"),
            "--output", out_dir,
        ]
    if os.path.exists(d8_script):
        return [
            d8_script, "--min-api", MIN_SDK, "--output", out_dir,
        ]
    sys.exit(
        "error: no dexer found in %s (looked for lib/dx.jar, lib/d8.jar, d8).\n"
        "Install build-tools 30 or newer, or point --build-tools at one." % T["build_tools"]
    )


ADDITIONAL_ZIP_ENTRIES = [
    (os.path.join(MAIN, "assets"), "assets"),
]


def zip_add_tree(zf, src_dir, prefix):
    for dirpath, _dirnames, filenames in os.walk(src_dir):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, src_dir).replace(os.sep, "/")
            zf.write(full, "%s/%s" % (prefix, rel) if prefix else rel)


def ensure_keystore(sh, T, path, alias, passwd, cn):
    """Creates the keystore on first run so a fresh clone can always produce signed artifacts."""
    if os.path.exists(path):
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sh.run([
        os.path.join(T["jdk"], "bin", "keytool"), "-genkeypair",
        "-keystore", path,
        "-storepass", passwd,
        "-keypass", passwd,
        "-alias", alias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", cn,
    ], quiet=True)
    print("    created keystore %s" % os.path.basename(path))


def stage_apk(sh, T, base_apk, dexs, out_apk, keystore, alias, passwd):
    unsigned = out_apk + ".unsigned"
    shutil.copyfile(base_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as zf:
        for index, dex in enumerate(dexs):
            zf.write(dex, "classes.dex" if index == 0 else "classes%d.dex" % (index + 1))
        for src, prefix in ADDITIONAL_ZIP_ENTRIES:
            if os.path.isdir(src):
                zip_add_tree(zf, src, prefix)
    aligned = out_apk + ".aligned"
    sh.run([os.path.join(T["build_tools"], "zipalign"), "-f", "-p", "4", unsigned, aligned], quiet=True)
    sh.run(
        [
            T["java"],
            "-jar",
            os.path.join(T["build_tools"], "lib", "apksigner.jar"),
            "sign",
            "--ks", keystore,
            "--ks-pass", "pass:" + passwd,
            "--key-pass", "pass:" + passwd,
            "--ks-key-alias", alias,
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--out", out_apk,
            aligned,
        ],
        quiet=True,
    )
    verify = sh.run(
        [T["java"], "-jar", os.path.join(T["build_tools"], "lib", "apksigner.jar"),
         "verify", "--verbose", out_apk], quiet=True
    )
    os.remove(unsigned)
    os.remove(aligned)
    return verify


# --- Android App Bundle (no bundletool: assemble the module zip directly) ----

def pb_varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def pb_tag(field, wire):
    return pb_varint((field << 3) | wire)


def pb_str(field, value):
    if isinstance(value, str):
        value = value.encode()
    return pb_tag(field, 2) + pb_varint(len(value)) + value


def bundle_config_pb():
    """A minimal but complete BundleConfig.pb (bundletool 1.15 compatible defaults)."""
    bundletool = pb_str(1, "1.15.6")            # Bundletool.version
    optimizations = b""                          # Optimizations - all defaults
    compression = b""
    for glob in ("**/classes*.dex", "**/*.resources.pb"):
        compression += pb_str(1, glob)           # Compression.uncompressed_glob
    return (
        pb_str(1, bundletool)                    # BundleConfig.bundletool
        + pb_str(2, optimizations)               # BundleConfig.optimizations
        + pb_str(3, compression)                 # BundleConfig.compression
    )


def stage_aab(sh, T, proto_apk, dexs, out_aab):
    entries = []
    with zipfile.ZipFile(proto_apk) as zf:
        for name in zf.namelist():
            if name == "AndroidManifest.xml":
                entries.append(("base/manifest/AndroidManifest.xml", zf.read(name)))
            elif name == "resources.pb":
                entries.append(("base/resources.pb", zf.read(name)))
            elif name.startswith("res/"):
                entries.append(("base/" + name, zf.read(name)))
    for index, dex in enumerate(dexs):
        name = "classes.dex" if index == 0 else "classes%d.dex" % (index + 1)
        with open(dex, "rb") as fh:
            entries.append(("base/dex/" + name, fh.read()))
    for src, prefix in ADDITIONAL_ZIP_ENTRIES:
        if os.path.isdir(src):
            for dirpath, _dirnames, filenames in os.walk(src):
                for fn in filenames:
                    full = os.path.join(dirpath, fn)
                    rel = os.path.relpath(full, src).replace(os.sep, "/")
                    with open(full, "rb") as fh:
                        entries.append(("base/%s/%s" % (prefix, rel), fh.read()))
    entries.append(("BundleConfig.pb", bundle_config_pb()))
    if os.path.exists(out_aab):
        os.remove(out_aab)
    with zipfile.ZipFile(out_aab, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name, data in entries:
            zf.writestr(name, data)
    return out_aab


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description="MorseCode offline APK/AAB builder")
    ap.add_argument("--jdk")
    ap.add_argument("--build-tools")
    ap.add_argument("--platforms")
    ap.add_argument("--kotlinc")
    ap.add_argument("--aapt2")
    ap.add_argument("--keystore", default=os.path.join(ROOT, "keystore", "morsecode-release.jks"))
    ap.add_argument("--ks-alias", default="morsecode")
    ap.add_argument("--ks-pass", default="morsecode")
    ap.add_argument("--debug-keystore", default=os.path.join(ROOT, "keystore", "debug.keystore"))
    ap.add_argument("--skip-aab", action="store_true")
    ap.add_argument("--no-clean", action="store_true")
    ap.add_argument("--dist", default=DIST)
    args = ap.parse_args()

    print("MorseCode offline build %s (%s)" % (VERSION_NAME, VERSION_CODE))
    if not args.no_clean:
        shutil.rmtree(BUILD, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(args.dist, exist_ok=True)

    T = tools(args)
    env = check_env(T)
    sh = Sh(env)
    shutil.copyfile(T["aapt2"], os.path.join(BUILD, "aapt2.tmp")) if not os.access(T["aapt2"], os.X_OK) else None
    if not os.access(T["aapt2"], os.X_OK):
        os.chmod(T["aapt2"], 0o755)

    print("[1/6] resources (binary)")
    base_apk, r_txt = stage_resources(sh, T, proto=False)
    print("[2/6] resources (proto, for .aab)")
    proto_apk = os.path.join(BUILD, "base-proto.apk")
    if not args.skip_aab:
        stage_resources(sh, T, proto=True)
    print("[3/6] R.kt + Kotlin")
    r_kt = os.path.join(BUILD, "R.kt")
    gen_r_kt(r_txt, r_kt)
    class_dirs = stage_kotlin(sh, T, r_kt, None)
    print("[4/6] dex")
    dexs = stage_dex(sh, T, class_dirs)
    print("[5/6] package + sign APKs")
    ensure_keystore(sh, T, args.keystore, args.ks_alias, args.ks_pass,
                    "CN=MorseCode Release,O=MorseCode,C=NG")
    ensure_keystore(sh, T, args.debug_keystore, "androiddebugkey", "android",
                    "CN=Android Debug,O=Android,C=US")
    rel = os.path.join(args.dist, "MorseCode-%s-release.apk" % VERSION_NAME)
    dbg = os.path.join(args.dist, "MorseCode-%s-debug.apk" % VERSION_NAME)
    stage_apk(sh, T, base_apk, dexs, rel, args.keystore, args.ks_alias, args.ks_pass)
    stage_apk(sh, T, base_apk, dexs, dbg, args.debug_keystore, "androiddebugkey", "android")
    print("[6/6] Android App Bundle")
    if not args.skip_aab:
        aab = os.path.join(args.dist, "MorseCode-%s.aab" % VERSION_NAME)
        stage_aab(sh, T, proto_apk, dexs, aab)
    # Fail here rather than on a phone: a build that compiles but leaves the Kotlin runtime out
    # produces an APK that dies with NoClassDefFoundError on the first frame.
    print("[check] runtime dependencies")
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import dexcheck  # noqa: E402

    jar = os.path.join(T["platforms"], "android-34", "android.jar")
    checked = 0
    for fn in sorted(os.listdir(args.dist)):
        if not fn.endswith(".apk"):
            continue
        path = os.path.join(args.dist, fn)
        defined, missing = dexcheck.check(path, jar)
        if missing:
            print("    %s: %d classes defined, MISSING %d" % (fn, defined, len(missing)))
            for package, count in dexcheck.summarise(missing):
                print("       %-42s %d reference(s)" % (package, count))
            sys.exit("error: %s references %d class(es) that are not bundled and not part of the "
                     "Android platform - see tools/dexcheck.py" % (fn, len(missing)))
        print("    %s: %d classes defined, every referenced class provided" % (fn, defined))
        checked += 1
    if not checked:
        sys.exit("error: no APK was produced in %s" % args.dist)

    print("\nArtifacts in %s:" % args.dist)
    for fn in sorted(os.listdir(args.dist)):
        p = os.path.join(args.dist, fn)
        print("  %-36s %6.2f MB" % (fn, os.path.getsize(p) / 1048576.0))


if __name__ == "__main__":
    main()
