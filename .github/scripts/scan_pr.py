"""Run free scanners on PR Java files and retain findings overlapping added lines."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


MAX_FINDINGS = 50
MAX_REPORT_BYTES = 100_000


def git(*args):
    return subprocess.check_output(
        ["git", "--no-pager", *args], text=True, encoding="utf-8"
    )


def added_ranges(patch):
    """This parser expects a zero-context git diff, not GitHub's contextual patch."""
    result = []
    for line in patch.splitlines():
        match = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if match:
            start, count = int(match[1]), int(match[2] or 1)
            if count:
                result.append((start, start + count - 1))
    return result


def overlaps(ranges, start, end):
    return any(start <= last and end >= first for first, last in ranges)


def changed_java(base, head):
    paths = git("diff", "--no-renames", "--name-only", "-z", "--diff-filter=AM",
                base, head, "--", "*.java").split("\0")
    changed = {}
    root = Path.cwd().resolve()
    for name in filter(None, paths):
        path = Path(name)
        # File lists cannot represent newlines. Refuse unusual paths rather than miss a file.
        if (path.is_absolute() or any(char in name for char in "\r\n\0")
                or path.is_symlink() or not path.resolve().is_relative_to(root)):
            raise ValueError("Unsafe Java file path in PR")
        if path.stat().st_size > 1_000_000:
            raise ValueError("Changed Java file exceeds the scanner's 1 MB limit")
        patch = git("diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--unified=0",
                    base, head, "--", name)
        ranges = added_ranges(patch)
        if ranges:
            changed[name] = ranges
    if len(changed) > 300:
        raise ValueError("PR exceeds the 300 changed Java file scan limit")
    return changed


def normalize_path(name):
    path = Path(name)
    if path.is_absolute():
        path = path.relative_to(Path.cwd())
    return path.as_posix().removeprefix("./")


def normalize_findings(semgrep, pmd, changed):
    findings = []

    def add(tool, path, line, end, rule, severity, message):
        path = normalize_path(path)
        line, end = int(line), int(end)
        if overlaps(changed.get(path, []), line, end):
            findings.append({"tool": tool, "path": path, "line": line, "end_line": end,
                             "rule": rule[:200], "severity": severity,
                             "message": message.strip()[:600]})

    for match in semgrep.get("results", []):
        add("Semgrep", match["path"], match["start"]["line"], match["end"]["line"],
            match["check_id"], match["extra"]["severity"].upper(), match["extra"]["message"])
    for file in pmd.get("files", []):
        for match in file["violations"]:
            severity = "ERROR" if match["priority"] <= 2 else "WARNING"
            add("PMD", file["filename"], match["beginline"], match["endline"],
                match["rule"], severity, match["description"])
    unique = {(f["tool"], f["path"], f["line"], f["rule"]): f for f in findings}
    return sorted(unique.values(), key=lambda f: (f["path"], f["line"], f["tool"], f["rule"]))


def run_scanners(changed, baseline, output_dir):
    semgrep_path, pmd_path = output_dir / "semgrep.json", output_dir / "pmd.json"
    # Arguments are passed directly: PR-controlled file names never become shell commands.
    semgrep = subprocess.run([
        "semgrep", "scan", "--oss-only", "--config", "p/java", "--metrics", "off",
        "--disable-version-check", "--baseline-commit", baseline, "--strict",
        "--json-output", str(semgrep_path), "--quiet", "--", *changed,
    ], check=False, timeout=480)
    if semgrep.returncode != 0:
        raise RuntimeError(f"Semgrep failed (exit {semgrep.returncode})")
    file_list = output_dir / "java-files.txt"
    file_list.write_text("\n".join(str(Path(name).resolve()) for name in changed) + "\n")
    pmd = subprocess.run([
        "pmd", "check", "--file-list", str(file_list), "--rulesets", ".github/pmd-ruleset.xml",
        "--use-version", "java-21", "--format", "json", "--report-file", str(pmd_path),
        "--no-cache", "--no-fail-on-violation",
    ], check=False, timeout=300)
    if pmd.returncode != 0:
        raise RuntimeError(f"PMD failed (exit {pmd.returncode})")
    semgrep_data = json.loads(semgrep_path.read_text())
    pmd_data = json.loads(pmd_path.read_text())
    if semgrep_data.get("errors") or pmd_data.get("processingErrors") or pmd_data.get("configurationErrors"):
        raise RuntimeError("A scanner reported incomplete analysis; inspect the scan logs")
    return normalize_findings(semgrep_data, pmd_data, changed)


def main():
    report_path = Path(os.environ["REPORT_PATH"])
    report_path.parent.mkdir(parents=True, exist_ok=True)
    head, base = os.environ["HEAD_SHA"], os.environ["BASE_SHA"]
    report = {"version": 1, "pr_number": int(os.environ["PR_NUMBER"]),
              "head_sha": head, "base_sha": base, "status": "error", "findings": [],
              "total_findings": 0, "changed_java_files": 0}
    try:
        if not re.fullmatch(r"[0-9a-f]{40}", head) or not re.fullmatch(r"[0-9a-f]{40}", base):
            raise ValueError("Invalid commit SHA")
        if git("rev-parse", "HEAD").strip() != head:
            raise ValueError("Checkout does not match the PR head")
        baseline = git("merge-base", base, head).strip()
        changed = changed_java(baseline, head)
        report["changed_java_files"] = len(changed)
        with tempfile.TemporaryDirectory(prefix="pr-scanners-") as temporary:
            findings = run_scanners(changed, baseline, Path(temporary)) if changed else []
        report.update(status="complete", findings=findings[:MAX_FINDINGS], total_findings=len(findings))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        # Errors must never be published as a clean scan or close an existing finding issue.
        print(f"Scan could not complete: {type(error).__name__}: {error}")
    encoded = json.dumps(report, ensure_ascii=True)
    if len(encoded.encode()) > MAX_REPORT_BYTES:
        raise RuntimeError("Scan report exceeded its size limit")
    report_path.write_text(encoded + "\n")
    if summary_path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary_path, "a") as summary:
            summary.write(f"### PR security and quality\n\nStatus: {report['status']}\n\n"
                          f"Changed Java files: {report['changed_java_files']}\n\n"
                          f"New findings on changed lines: {report['total_findings']}\n")
    return 2 if report["status"] != "complete" else int(report["total_findings"] > 0)


if __name__ == "__main__":
    raise SystemExit(main())
