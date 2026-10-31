#!/usr/bin/env python3
"""Summarize a Gradle gate run: compile errors, failed tasks, failing tests per module.

usage: junit_summary.py <root> [gradle.log ...]

Reads every JUnit XML under <root>/**/build/test-results/ (included builds too) and the given
Gradle console logs. Used by PR CI (the run page summary) and by the session on downloaded
results (#1450). Exits 0; the Gradle exit status is the gate.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def log_lines(paths):
    errors, failed_tasks = [], []
    for path in paths:
        try:
            text = Path(path).read_text(errors="replace")
        except OSError:
            continue  # a step that never ran leaves no log
        for line in text.splitlines():
            if line.startswith("e: "):
                errors.append(line[:300])
            match = re.search(r"Execution failed for task '([^']+)'", line)
            if match and match.group(1) not in failed_tasks:
                failed_tasks.append(match.group(1))
    return errors, failed_tasks


def junit(root):
    modules = {}
    failures = []
    for path in sorted(root.glob("**/build/test-results/**/*.xml")):
        if "node_modules" in path.parts:
            continue
        module = str(path.relative_to(root)).split("/build/test-results/")[0]
        try:
            suites = ET.parse(path).getroot()
        except ET.ParseError as error:
            failures.append(f"UNREADABLE {path}: {error}")
            continue
        totals = modules.setdefault(module, [0, 0, 0])
        for suite in suites.iter("testsuite"):
            totals[0] += int(suite.get("tests", 0))
            totals[1] += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
            totals[2] += int(suite.get("skipped", 0))
            for case in suite.iter("testcase"):
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                if failure is not None:
                    message = (failure.get("message") or "").replace("\n", " ")[:400]
                    name = case.get("classname", "?").split(".")[-1]
                    failures.append(f"FAIL {module} {name} {case.get('name', '?')[:100]} | {message}")
    return modules, failures


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        return 2
    root = Path(sys.argv[1])
    errors, failed_tasks = log_lines(sys.argv[2:])
    modules, failures = junit(root)

    print("## Gate summary\n")
    print("```")
    if errors:
        print(f"Compile errors ({len(errors)}):")
        print("\n".join(errors[:30]))
    if failed_tasks:
        print("Failed tasks: " + ", ".join(failed_tasks))
    if failures:
        print(f"Failing tests ({len(failures)}):")
        print("\n".join(failures[:60]))
    if not modules:
        print("No JUnit results found (tests did not run).")
    for module, (tests, failed, skipped) in sorted(modules.items()):
        print(f"{module}: {tests} tests, {failed} failed, {skipped} skipped")
    print("```")
    return 0


if __name__ == "__main__":
    sys.exit(main())
