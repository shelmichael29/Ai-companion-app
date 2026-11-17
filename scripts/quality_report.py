#!/usr/bin/env python3
"""Builds the "Penelope Development Quality Report" from real build outputs.

Reads test results (JUnit XML), Android Lint, Detekt and Kover reports from */build, plus the
outcome of each step passed on the command line. A check is only reported PASS when its step
actually ran and succeeded; a check that didn't run is NOT RUN, never PASS.

Usage (CI and scripts/quality-gate.sh):
  python3 scripts/quality_report.py --stage "..." --branch B --commit C \
      --build success --detekt success --tests success --lint success --security success \
      [--release success|skipped] [--out build/quality/QUALITY_REPORT.md]

Step outcomes use GitHub's words: success, failure, cancelled, skipped (or empty = not run).
Exit code: 0 if no critical check failed, 1 otherwise (so the report itself can gate).
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import xml.etree.ElementTree as ET

# Test categories, by convention (see docs/DEVELOPMENT_QUALITY_GATES.md):
# - Regression: package `regression` or class name contains "Regression".
# - Integration: several real components together or a rendered screen: Robolectric UI tests
#   (package `ui`, `quality`), simulated-hardware sessions and loopback sockets.
# - Accessibility: the screen audit, theme contrast and dark-mode text tests.
# - Unit: everything else.
INTEGRATION = re.compile(r"(Integration|Loopback|Manager|Session|Robustness|Resilience|Settings|UiTest|DashboardTest|Reporting)")
ACCESSIBILITY = {"AccessibilityAuditTest", "UiPolishTest", "SettingsThemeTextTest"}


def outcome(value: str | None) -> str:
    v = (value or "").strip().lower()
    return {"success": "PASS", "failure": "FAIL", "cancelled": "NOT RUN", "skipped": "NOT RUN", "": "NOT RUN"}.get(v, "NOT RUN")


def category(classname: str) -> str:
    simple = classname.rsplit(".", 1)[-1]
    if ".regression." in classname or "Regression" in simple:
        return "regression"
    if simple in ACCESSIBILITY:
        return "accessibility"
    if ".ui." in classname or ".quality." in classname or INTEGRATION.search(simple):
        return "integration"
    return "unit"


def read_tests():
    stats = {c: {"run": 0, "failed": 0, "skipped": 0} for c in ("unit", "integration", "regression", "accessibility")}
    failures: list[str] = []
    for path in glob.glob("*/build/test-results/**/*.xml", recursive=True):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            cls = case.get("classname", "")
            c = stats[category(cls)]
            c["run"] += 1
            if case.find("skipped") is not None:
                c["skipped"] += 1
            elif case.find("failure") is not None or case.find("error") is not None:
                c["failed"] += 1
                failures.append(f"{cls.rsplit('.', 1)[-1]} › {case.get('name')}")
    return stats, failures


def read_lint():
    errors = warnings = 0
    a11y_errors = 0
    found = False
    for path in glob.glob("*/build/reports/lint-results-debug.xml"):
        found = True
        for issue in ET.parse(path).getroot().iter("issue"):
            sev = issue.get("severity")
            if sev in ("Error", "Fatal"):
                errors += 1
                if issue.get("category", "").startswith("Accessibility"):
                    a11y_errors += 1
            else:
                warnings += 1
    return (errors, warnings, a11y_errors) if found else None


def read_detekt():
    found = False
    issues = 0
    for path in glob.glob("*/build/reports/detekt/detekt.xml"):
        found = True
        issues += sum(1 for _ in ET.parse(path).getroot().iter("error"))
    return issues if found else None


def read_coverage():
    rows = []
    for path in sorted(glob.glob("*/build/reports/kover/report*.xml")):
        root = ET.parse(path).getroot()
        for counter in root.findall("counter"):
            if counter.get("type") == "LINE":
                missed, covered = int(counter.get("missed")), int(counter.get("covered"))
                rows.append((path.split("/")[0], 100.0 * covered / max(1, missed + covered)))
    return rows


def status_from_tests(step: str, s: dict) -> str:
    if s["run"] == 0:
        return "NOT RUN"
    if s["failed"]:
        return "FAIL"
    return "PASS" if step == "PASS" else step


def main() -> int:
    ap = argparse.ArgumentParser()
    for name in ("stage", "branch", "commit", "build", "detekt", "tests", "lint", "security", "release", "out"):
        ap.add_argument(f"--{name}", default="")
    a = ap.parse_args()

    stats, failures = read_tests()
    lint = read_lint()
    detekt_issues = read_detekt()
    coverage = read_coverage()

    tests_step = outcome(a.tests)
    rows = [
        ("Android build", outcome(a.build)),
        ("Android Lint", outcome(a.lint)),
        ("Detekt", outcome(a.detekt)),
        ("Unit tests", status_from_tests(tests_step, stats["unit"])),
        ("Integration tests", status_from_tests(tests_step, stats["integration"])),
        ("Regression tests", status_from_tests(tests_step, stats["regression"])),
        ("Security checks", outcome(a.security)),
        ("Accessibility checks", status_from_tests(tests_step, stats["accessibility"])
            if not (lint and lint[2]) else "FAIL"),
        ("Device testing", "NOT RUN"),
    ]
    if a.release:
        rows.insert(1, ("Release build", outcome(a.release)))

    critical_failed = [name for name, result in rows if result == "FAIL"]
    critical_missing = [name for name, result in rows
                        if result == "NOT RUN" and name not in ("Device testing", "Release build")]
    if critical_failed:
        overall = "BLOCKED"
    elif critical_missing:
        overall = "BLOCKED"  # a required automated check didn't run
    else:
        overall = "NEEDS MANUAL VERIFICATION"

    total = {k: sum(s[k] for s in stats.values()) for k in ("run", "failed", "skipped")}
    out: list[str] = []
    out.append("# Penelope Development Quality Report\n")
    out.append(f"**Stage:** {a.stage or 'not specified'}  ")
    out.append(f"**Branch:** {a.branch or 'unknown'}  ")
    out.append(f"**Commit:** {a.commit[:12] if a.commit else 'unknown'}\n")
    out.append("| Check | Result |\n|---|---|")
    out.extend(f"| {n} | {r} |" for n, r in rows)
    out.append("")
    out.append(f"**Overall stage status: {overall}**")
    if overall == "NEEDS MANUAL VERIFICATION":
        out.append("All automated checks passed. Device, adapter and vehicle checks are manual (see below).")
    out.append("\n## Tests executed (automated, JVM)\n")
    out.append("| Category | Run | Passed | Failed | Skipped |\n|---|---|---|---|---|")
    for cat, s in stats.items():
        out.append(f"| {cat.capitalize()} | {s['run']} | {s['run'] - s['failed'] - s['skipped']} | {s['failed']} | {s['skipped']} |")
    out.append(f"| **Total** | **{total['run']}** | **{total['run'] - total['failed'] - total['skipped']}** | **{total['failed']}** | **{total['skipped']}** |")
    if failures:
        out.append("\n### Failed tests")
        out.extend(f"- ❌ {f}" for f in failures[:50])

    out.append("\n## Static analysis")
    if lint:
        out.append(f"- Android Lint: {lint[0]} errors ({lint[2]} accessibility), {lint[1]} warnings.")
    else:
        out.append("- Android Lint: NOT RUN (no report found).")
    out.append(f"- Detekt: {detekt_issues} issue(s)." if detekt_issues is not None else "- Detekt: NOT RUN (no report found).")
    if coverage:
        out.append("- Line coverage (informational): " + ", ".join(f"{m} {p:.1f}%" for m, p in coverage) + ".")

    out.append("\n## Not covered by automation (manual verification)")
    out.append("- **Physical Android device (S26 Ultra):** voice recognition and speech, TalkBack navigation, "
               "animation smoothness, Bluetooth permissions, App Check token, Crashlytics test crash.")
    out.append("- **Real OBD-II adapter and vehicle (2011 BMW 528i):** pairing, protocol detection, live data, "
               "trouble-code reads. Automated tests use simulated adapters only; no test sends clear, reset or "
               "coding commands.")
    out.append("- **Real Gemini requests:** need a configured Firebase build and a registered App Check token.")

    text = "\n".join(out) + "\n"
    if a.out:
        os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
        with open(a.out, "w", encoding="utf-8") as f:
            f.write(text)
    print(text)
    return 1 if critical_failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
