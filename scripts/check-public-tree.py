#!/usr/bin/env python3
"""Flag common private material in publishable files and all reachable Git objects.

Print locations/categories only. Complements Gitleaks; cannot prove absence of secrets.
Ignored local build caches and machine configuration are not publication inputs.
"""
import argparse
import re
import subprocess
from pathlib import Path


def git(*args):
    return subprocess.check_output(["git", *args])


RULES = {
    "personal email": re.compile(rb"[\w.+-]+@(?!example\.(?:com|org|invalid)\b|users\.noreply\.github\.com\b)[\w.-]+\.[A-Za-z]{2,}"),
    "absolute user path": re.compile(rb"(?:/Users/|/home/|[A-Za-z]:[\\/]Users[\\/])[A-Za-z0-9_.-]+"),
    "private key": re.compile(rb"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
    "advertising account": re.compile(rb"ca-app-pub-(?!3940256099942544)[0-9]{16}"),
    "hardware address": re.compile(rb"\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b"),
}
PRIVATE_FILE = re.compile(
    r"(?:^|/)(?:\.env(?:\..+)?|keystore\.properties|admob\.properties|local\.properties|"
    r"google-services\.json|service-account[^/]*\.json)$|"
    r"\.(?:jks|keystore|p12|pfx|pem|key|db(?:-[^/]*)?|sqlite3?|hprof|apk|aab|log|dump|trace)$",
    re.I,
)
findings = set()


def check(label, data):
    for category, pattern in RULES.items():
        for match in pattern.finditer(data):
            line = data.count(b"\n", 0, match.start()) + 1
            findings.add((label, line, category))


def check_name(name):
    if PRIVATE_FILE.search(name) and name != ".env.example":
        findings.add((name, 0, "private file type"))
    check("path: " + name, name.encode())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--working-tree-only", action="store_true")
    args = parser.parse_args()
    root = Path(git("rev-parse", "--show-toplevel").decode().strip())
    names = git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0")
    for raw in set(names) - {b""}:
        name = raw.decode()
        path = root / name
        if not path.exists():
            continue
        check_name(name)
        if path.is_symlink():
            findings.add((name, 0, "symlink requires manual review"))
        else:
            check(name, path.read_bytes())
    objects = 0
    if not args.working_tree_only:
        for row in git("rev-list", "--objects", "--all").splitlines():
            oid, _, raw_name = row.partition(b" ")
            if raw_name:
                check_name(raw_name.decode())
            kind = git("cat-file", "-t", oid.decode()).strip()
            if kind in (b"blob", b"commit", b"tag"):
                objects += 1
                check("object " + oid.decode()[:12], git("cat-file", kind.decode(), oid.decode()))
    for label, line, category in sorted(findings):
        print(f"{label}:{line}: {category}")
    print(f"Checked publication inputs and {objects} Git objects; {len(findings)} findings.")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
