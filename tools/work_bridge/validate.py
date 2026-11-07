#!/usr/bin/env python3
"""Fail-closed Work Bridge PR authorization check; standard library only."""

from __future__ import annotations
import argparse
import fnmatch
import json
import re
import subprocess
from pathlib import PurePosixPath

BLOCK_RE = re.compile(r"<!--\s*WB_TASK_V1\s*(\{.*?\})\s*-->", re.DOTALL)
TASK_RE = re.compile(r"^WB-[0-9]{3}$")
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
BRANCH_RE = re.compile(r"^bridge/task-(WB-[0-9]{3})-[a-z0-9][a-z0-9-]*$")


class PolicyError(Exception):
    pass


def git(repo: str, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", repo, *args], capture_output=True, text=True,
        encoding="utf-8", errors="replace", check=False,
    )
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise PolicyError(f"git {' '.join(args)} failed ({result.returncode}): {detail}")
    return result.stdout.strip()


def parse_manifest(inbox: str, task_id: str) -> dict:
    if not TASK_RE.fullmatch(task_id):
        raise PolicyError(f"Invalid Task ID: {task_id!r}")
    found = []
    for raw in BLOCK_RE.findall(inbox):
        try:
            item = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise PolicyError(f"Malformed WB_TASK_V1 JSON: {exc}") from exc
        if not isinstance(item, dict):
            raise PolicyError("WB_TASK_V1 must contain a JSON object")
        candidate = item.get("id")
        if not isinstance(candidate, str) or not TASK_RE.fullmatch(candidate):
            raise PolicyError("WB_TASK_V1 contains an invalid id")
        if candidate == task_id:
            found.append(item)
    if len(found) != 1:
        raise PolicyError(f"Expected exactly one manifest for {task_id}; found {len(found)}")
    item = found[0]
    if item.get("status") != "READY":
        raise PolicyError(f"{task_id} is not READY in base INBOX (status={item.get('status')!r})")
    if item.get("base_branch") != "bridge/work-bridge-bootstrap":
        raise PolicyError("Manifest base_branch is not bridge/work-bridge-bootstrap")
    allowed = item.get("allowed_paths")
    if not isinstance(allowed, list) or not allowed:
        raise PolicyError("allowed_paths must be a non-empty list")
    if any(not isinstance(pattern, str) for pattern in allowed):
        raise PolicyError("Every allowed_paths entry must be a string")
    if len(set(allowed)) != len(allowed):
        raise PolicyError("allowed_paths must not contain duplicates")
    for pattern in allowed:
        if (not pattern or pattern.startswith("/")
                or "\\" in pattern or any(p in {"", ".", ".."} for p in PurePosixPath(pattern).parts)
                or pattern in {"*", "**", "**/*"}):
            raise PolicyError(f"Unsafe allowed_paths pattern: {pattern!r}")
    return item


def changed_paths_from_status(output: str) -> list[str]:
    result = []
    for line in output.splitlines():
        if not line:
            continue
        parts = line.split("\t", 1)
        if len(parts) != 2:
            raise PolicyError(f"Malformed git name-status line: {line!r}")
        status, path = parts
        if status not in {"A", "M"}:
            raise PolicyError(f"Disallowed file operation {status}: {path}")
        result.append(path)
    return result


def validate(*, inbox: str, current_base_inbox: str, report: str, task_id: str, head_branch: str,
             base_branch: str, declared_base_sha: str, merge_base: str,
             changed_paths: list[str], head_repo: str, base_repo: str) -> list[str]:
    errors = []
    match = BRANCH_RE.fullmatch(head_branch)
    if not match:
        errors.append("Head branch must match bridge/task-WB-NNN-<slug>")
    elif match.group(1) != task_id:
        errors.append("Task ID does not match head branch")
    if base_branch != "bridge/work-bridge-bootstrap":
        errors.append("PR base must be bridge/work-bridge-bootstrap")
    if head_repo != base_repo:
        errors.append("Cross-repository task PRs are not accepted")
    if not TASK_RE.fullmatch(task_id):
        errors.append("Invalid or missing Task ID")
    if not SHA_RE.fullmatch(declared_base_sha) or not SHA_RE.fullmatch(merge_base):
        errors.append("Base SHA values must be full 40-character commit SHAs")
    elif declared_base_sha != merge_base:
        errors.append("Declared Base SHA does not match actual PR merge-base")
    if errors:
        return errors

    try:
        manifest = parse_manifest(inbox, task_id)
        current_manifest = parse_manifest(current_base_inbox, task_id)
    except PolicyError as exc:
        return [str(exc)]
    if current_manifest != manifest:
        return ["Task authorization in current base INBOX changed or was revoked after the task branch base"]

    report_path = f"AI_MASTER_BRIDGE/REPORTS/{task_id}.md"
    if report_path not in changed_paths:
        errors.append(f"Required task report missing: {report_path}")
    if not re.search(rf"(?im)^\s*(?:[-*]\s*)?Task ID:\s*(?:\x60)?{re.escape(task_id)}(?:\x60)?\s*$", report):
        errors.append("Report does not contain the exact Task ID")
    if not re.search(rf"(?im)^\s*(?:[-*]\s*)?Base SHA:\s*(?:\x60)?{re.escape(declared_base_sha)}(?:\x60)?\s*$", report):
        errors.append("Report does not contain the exact Base SHA")

    for path in changed_paths:
        if (not path or path.startswith("/") or "\\" in path
                or any(p in {"", ".", ".."} for p in PurePosixPath(path).parts)):
            errors.append(f"Unsafe changed path: {path!r}")
            continue
        if not any(fnmatch.fnmatchcase(path, pattern) for pattern in manifest["allowed_paths"]):
            errors.append(f"Changed path is not authorized: {path}")
    return errors


def validate_repo(repo: str, base_sha: str, head_sha: str, base_branch: str,
                  head_branch: str, head_repo: str, base_repo: str) -> list[str]:
    if not SHA_RE.fullmatch(base_sha) or not SHA_RE.fullmatch(head_sha):
        return ["GitHub event supplied an invalid Base SHA or Head SHA"]
    try:
        merge_base = git(repo, "merge-base", base_sha, head_sha)
        paths = changed_paths_from_status(git(
            repo, "diff", "--name-status", "--no-renames", f"{merge_base}...{head_sha}"
        ))
        inbox = git(repo, "show", f"{merge_base}:AI_MASTER_BRIDGE/INBOX.md")
        current_base_inbox = git(repo, "show", f"{base_sha}:AI_MASTER_BRIDGE/INBOX.md")
        match = BRANCH_RE.fullmatch(head_branch)
        if not match:
            raise PolicyError("Invalid task branch name")
        task_id = match.group(1)
        report = git(repo, "show", f"{head_sha}:AI_MASTER_BRIDGE/REPORTS/{task_id}.md")
        base_match = re.search(
            r"(?im)^\s*(?:[-*]\s*)?Base SHA:\s*(?:\x60)?([0-9a-f]{40})(?:\x60)?\s*$", report
        )
        declared = base_match.group(1) if base_match else ""
        return validate(
            inbox=inbox, current_base_inbox=current_base_inbox, report=report,
            task_id=task_id, head_branch=head_branch,
            base_branch=base_branch, declared_base_sha=declared, merge_base=merge_base,
            changed_paths=paths, head_repo=head_repo, base_repo=base_repo,
        )
    except PolicyError as exc:
        return [str(exc)]


def main() -> int:
    parser = argparse.ArgumentParser(description="Fail-closed Work Bridge PR authorization")
    parser.add_argument("--repo", default=".")
    parser.add_argument("--base-sha", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--base-branch", required=True)
    parser.add_argument("--head-branch", required=True)
    parser.add_argument("--head-repo", required=True)
    parser.add_argument("--base-repo", required=True)
    args = parser.parse_args()
    errors = validate_repo(
        args.repo, args.base_sha, args.head_sha, args.base_branch,
        args.head_branch, args.head_repo, args.base_repo,
    )
    if errors:
        print("WORK BRIDGE AUTHORIZATION: FAIL")
        for error in errors:
            print(f" - {error}")
        return 1
    print("WORK BRIDGE AUTHORIZATION: PASS")
    print(" - Task ID, READY manifest, merge-base, report and changed-file allowlist verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
