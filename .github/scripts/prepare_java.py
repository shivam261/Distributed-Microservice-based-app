"""Build a temporary two-commit repository containing only the changed Java inputs."""

import os
from pathlib import Path
import re
import subprocess


def git(directory, *args):
    return subprocess.check_output(["git", "--literal-pathspecs", "-C", directory, *args])


def prepare(source, target, base, head):
    if not all(re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (base, head)):
        raise ValueError("Invalid source revision")
    baseline = git(source, "merge-base", base, head).decode().strip()
    paths = git(source, "diff", "--no-renames", "--name-only", "-z", "--diff-filter=AMT",
                baseline, head, "--").decode().split("\0")
    names = [name for name in paths if name.endswith(".java")]
    if len(names) > 300:
        raise ValueError("PR exceeds the Java file limit")
    for name in names:
        if (Path(name).is_absolute() or any(part.lower() in ("", ".", "..", ".git") for part in name.split("/"))
                or not name.isprintable() or "\\" in name):
            raise ValueError("Unsafe Java path")

    target.mkdir()
    git(target, "init", "-q")
    revisions = []
    for revision in (baseline, head):
        for name in names:
            record = git(source, "ls-tree", "-z", revision, "--", name)
            if not record:
                continue
            metadata, _ = record.split(b"\t", 1)
            mode, kind, oid = metadata.split()
            if mode not in (b"100644", b"100755") or kind != b"blob":
                raise ValueError("Java inputs must be regular files")
            if int(git(source, "cat-file", "-s", oid)) > 1_000_000:
                raise ValueError("Java input exceeds 1 MB")
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            contents = git(source, "cat-file", "blob", oid)
            if b"\0" in contents:
                raise ValueError("Binary Java input cannot be analyzed")
            path.write_bytes(contents)
        git(target, "add", "--force", "--", ".")
        git(target, "-c", "user.name=Scanner", "-c", "user.email=scanner@localhost",
            "commit", "-qm", revision, "--allow-empty")
        revisions.append(git(target, "rev-parse", "HEAD").decode().strip())
    return revisions


if __name__ == "__main__":
    before, after = prepare(Path.cwd(), Path(os.environ["SCAN_DIR"]), os.environ["BASE_SHA"], os.environ["HEAD_SHA"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"base={before}\nhead={after}\n")
