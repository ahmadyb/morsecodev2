#!/usr/bin/env python3
"""
Guard the rule that app/src/main/java/com/morsecode/app/compat/ exists to enforce.

The offline toolchain compiles that directory on its own, against android-34 and nothing else:
those files wrap APIs that the API-23 floor does not have, so they are allowed to use them. The
price is that they cannot see the rest of the app - no generated R class, no helpers from
core/ or feature/ - and a single slipped reference turns into "unresolved reference: R" from
the middle of a Kotlin compile, which is a slow way to learn it.

So: every compat source must reference only
  * platform classes (android.*, java.*, kotlin.*),
  * other compat sources,
  * an explicit allowlist of small, dependency-free helpers.

Usage:
    python3 tools/check_compat.py [compat-dir]

Exit status is 1 when something breaks the rule.
"""

import os
import re
import sys

# Helpers that are themselves compiled in the compat pass or are pure Kotlin/JVM with no app
# dependencies. Keep this list short: adding to it is how the rule quietly stops working.
ALLOWED_NON_PLATFORM = {
    "com.morsecode.app.core.model.MediaItem",
    "com.morsecode.app.core.util.Compat",
    "com.morsecode.app.core.util.Integrity",
    "com.morsecode.app.core.util.Paths",
}

PLATFORM_PREFIXES = ("android.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.")

# The generated resource class lives in the app's own package, which this pass never compiles.
FORBIDDEN_REFERENCES = (
    (re.compile(r"(^|[^\w.])R\.(string|drawable|id|color|dimen|layout|style|array|raw|mipmap|anim)\b"),
     "reference to the generated R class"),
    (re.compile(r"com\.morsecode\.app\.R\b"), "reference to the generated R class"),
)


def check(path):
    """Return a list of human-readable problems found in one compat source file."""
    problems = []
    src = open(path, encoding="utf-8").read()

    for pattern, what in FORBIDDEN_REFERENCES:
        for m in pattern.finditer(src):
            line = src[: m.start()].count("\n") + 1
            problems.append("%s:%d: %s (%s)" % (path, line, what, m.group(0).strip()))

    for m in re.finditer(r"^import\s+([\w.]+)", src, re.M):
        name = m.group(1)
        line = src[: m.start()].count("\n") + 1
        if name.startswith(PLATFORM_PREFIXES):
            continue
        if name in ALLOWED_NON_PLATFORM:
            continue
        if name.startswith("com.morsecode.app"):
            problems.append(
                "%s:%d: imports %s - not visible in the compat pass (see ALLOWED_NON_PLATFORM)"
                % (path, line, name)
            )
        else:
            problems.append("%s:%d: imports %s - compat may use platform classes only"
                            % (path, line, name))
    return problems


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    default = os.path.join(root, "app", "src", "main", "java", "com", "morsecode", "app", "compat")
    directory = argv[1] if len(argv) > 1 else default
    if not os.path.isdir(directory):
        print("error: no such directory: %s" % directory)
        return 1

    files = sorted(
        os.path.join(directory, n) for n in os.listdir(directory) if n.endswith(".kt")
    )
    if not files:
        print("error: no Kotlin sources in %s" % directory)
        return 1

    problems = []
    for f in files:
        problems += check(f)

    if problems:
        print("compat purity check failed (%d problem(s)):" % len(problems))
        for p in problems:
            print("  " + p)
        return 1
    print("compat purity ok: %d file(s), platform API only" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
