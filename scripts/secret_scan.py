#!/usr/bin/env python3
"""Scans the repository for committed secrets. Fails (exit 1) if any are found.

Usage:
  python3 scripts/secret_scan.py            # files tracked by git (current commit)
  python3 scripts/secret_scan.py --history  # also every committed version in git history

Findings are printed with the file, line and kind only. The matched value is NEVER printed, so
running this in CI can't leak the secret it found.

It looks for credentials that must never be in Git: private keys, Google service-account files,
Google/Gemini API keys, OAuth client secrets, GitHub and other access tokens, keystores,
google-services.json, App Check debug tokens written as assignments, and VINs in source files.
"""
from __future__ import annotations

import re
import subprocess
import sys

PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("Google service account", re.compile(r'"type"\s*:\s*"service_account"')),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("Google OAuth client secret", re.compile(r"\bGOCSPX-[0-9A-Za-z_\-]{20,}\b")),
    ("GitHub token", re.compile(r"\b(?:gh[pousr]_[0-9A-Za-z]{36,}|github_pat_[0-9A-Za-z_]{40,})\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[0-9A-Za-z\-]{10,}\b")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Anthropic/OpenAI style key", re.compile(r"\bsk-(?:ant-)?[A-Za-z0-9_\-]{32,}\b")),
    # App Check debug tokens are UUIDs; flag one only when assigned to a debug-token-like name.
    ("App Check debug token", re.compile(
        r"(?i)(?:debug[_\-\s]?(?:token|secret)|appcheck[_\-]?debug)\S{0,20}\s*[:=]\s*[\"']?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),
]

# 17-character VIN with a check digit position; only flagged in source/config, not test fixtures.
VIN = re.compile(r"\b[A-HJ-NPR-Z0-9]{8}[0-9X][A-HJ-NPR-Z0-9]{8}\b")
VIN_SCAN_EXTENSIONS = (".kt", ".kts", ".xml", ".json", ".properties", ".yml", ".yaml", ".md")
VIN_ALLOWED_PATHS = ("/test/", "/androidTest/", "docs/", "README.md", "scripts/secret_scan.py")

FORBIDDEN_FILES = (
    re.compile(r"(^|/)google-services\.json$"),
    re.compile(r"\.(?:jks|keystore|p12|pfx)$"),
    re.compile(r"(^|/)(?:keystore|secrets|signing)\.properties$"),
    re.compile(r"(^|/)local\.properties$"),
    re.compile(r"\.logcat$"),  # saved phone logs can contain debug tokens and device IDs
)

# Known-safe matches: public test fixtures that look like credentials. Path, kind.
ALLOWLIST: set[tuple[str, str]] = set()


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True, errors="replace").stdout


def scan_text(path: str, text: str) -> list[tuple[str, int, str]]:
    found: list[tuple[str, int, str]] = []
    for lineno, line in enumerate(text.splitlines(), 1):
        for kind, pattern in PATTERNS:
            if pattern.search(line) and (path, kind) not in ALLOWLIST:
                found.append((path, lineno, kind))
        if path.endswith(VIN_SCAN_EXTENSIONS) and not any(p in path for p in VIN_ALLOWED_PATHS):
            for m in VIN.finditer(line):
                token = m.group(0)
                # Real VINs mix letters and digits; skip hex/hash-like or all-digit strings.
                if re.search(r"[A-Z]", token) and re.search(r"\d", token) and not re.fullmatch(r"[0-9A-F]+", token):
                    found.append((path, lineno, "possible VIN"))
    return found


def scan_tree(ref: str | None = None, scanned: set[tuple[str, str]] | None = None) -> list[tuple[str, int, str]]:
    if ref is None:
        entries = [(path, None) for path in git("ls-files", "-z").split("\0") if path]
    else:
        entries = []
        for entry in git("ls-tree", "-rz", ref).split("\0"):
            if not entry:
                continue
            metadata, path = entry.split("\t", 1)
            _, kind, oid = metadata.split()
            if kind == "blob":
                entries.append((path, oid))
    findings: list[tuple[str, int, str]] = []
    for path, oid in entries:
        if oid is not None and scanned is not None:
            if (path, oid) in scanned:
                continue
            scanned.add((path, oid))
        label = path if ref is None else f"{ref[:7]}:{path}"
        if any(p.search(path) for p in FORBIDDEN_FILES):
            findings.append((label, 0, "file that must never be committed"))
            continue
        try:
            text = open(path, encoding="utf-8", errors="ignore").read() if ref is None else git("cat-file", "blob", oid)
        except FileNotFoundError:
            continue
        if "\0" in text[:4096]:
            continue  # binary
        findings.extend((label, line, kind) for _, line, kind in scan_text(path, text))
    return findings


def main() -> int:
    findings = scan_tree()
    if "--history" in sys.argv:
        seen = {(f[0], f[2]) for f in findings}
        scanned: set[tuple[str, str]] = set()
        for commit in git("rev-list", "--all").split():
            for f in scan_tree(commit, scanned):
                key = (f[0].split(":", 1)[1], f[2])
                if key not in seen:
                    seen.add(key)
                    findings.append(f)
    if findings:
        print(f"Secret scan: {len(findings)} finding(s). Values are not shown.")
        for path, line, kind in findings:
            print(f"  {path}:{line}  {kind}")
        print("Remove the secret, rotate it if it was ever pushed, and keep it in GitHub Secrets or Firebase instead.")
        return 1
    print("Secret scan: no secrets found" + (" in files or history." if "--history" in sys.argv else " in tracked files."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
