import json
import unittest
from validate import PolicyError, changed_paths_from_status, validate

SHA = "a" * 40
TASK = "WB-004"
BRANCH = "bridge/task-WB-004-ci-gate"
REPORT_PATH = f"AI_MASTER_BRIDGE/REPORTS/{TASK}.md"
ALLOWED = [
    ".github/workflows/work-bridge-authorization.yml",
    "tools/work_bridge/validate.py",
    "tools/work_bridge/test_validate.py",
    REPORT_PATH,
]


def make_inbox(status="READY"):
    data = {
        "id": TASK, "status": status,
        "base_branch": "bridge/work-bridge-bootstrap",
        "allowed_paths": ALLOWED,
    }
    return "<!-- WB_TASK_V1\n" + json.dumps(data, indent=2) + "\n-->\n"


def make_report(base_sha=SHA):
    return f"# WB-004 report\n\n- Task ID: {TASK}\n- Base SHA: \x60{base_sha}\x60\n"


class WorkBridgeTests(unittest.TestCase):
    def check(self, **overrides):
        args = {
            "inbox": make_inbox(), "current_base_inbox": make_inbox(), "report": make_report(),
            "task_id": TASK, "head_branch": BRANCH,
            "base_branch": "bridge/work-bridge-bootstrap",
            "declared_base_sha": SHA, "merge_base": SHA,
            "changed_paths": ALLOWED, "head_repo": "owner/repo",
            "base_repo": "owner/repo",
        }
        args.update(overrides)
        return validate(**args)

    def test_ready_task_and_allowlist_pass(self):
        self.assertEqual(self.check(), [])

    def test_draft_rejected(self):
        self.assertTrue(any("not READY" in e for e in self.check(inbox=make_inbox("DRAFT"))))

    def test_master_done_rejected(self):
        self.assertTrue(any("not READY" in e for e in self.check(inbox=make_inbox("MASTER_DONE"))))

    def test_missing_task_manifest_rejected(self):
        self.assertTrue(any("found 0" in e for e in self.check(inbox="# no manifest")))

    def test_revoked_task_in_current_base_is_rejected(self):
        errors = self.check(current_base_inbox=make_inbox("MASTER_DONE"))
        self.assertTrue(any("not READY" in e or "changed or was revoked" in e for e in errors))

    def test_duplicate_task_manifest_rejected(self):
        self.assertTrue(any("found 2" in e for e in self.check(inbox=make_inbox()+make_inbox())))

    def test_malformed_manifest_rejected(self):
        bad = "<!-- WB_TASK_V1 {invalid-json} -->"
        self.assertTrue(any("Malformed" in e for e in self.check(inbox=bad)))

    def test_base_sha_mismatch_rejected(self):
        self.assertTrue(any("does not match" in e for e in self.check(declared_base_sha="b"*40)))

    def test_unauthorized_file_rejected(self):
        errors = self.check(changed_paths=[REPORT_PATH, "app/src/main/Forbidden.kt"])
        self.assertTrue(any("not authorized" in e for e in errors))

    def test_non_string_allowlist_entry_rejected(self):
        data = {
            "id": TASK,
            "status": "READY",
            "base_branch": "bridge/work-bridge-bootstrap",
            "allowed_paths": [{"unexpected": "object"}],
        }
        bad_inbox = "<!-- WB_TASK_V1\n" + json.dumps(data) + "\n-->\n"
        errors = self.check(inbox=bad_inbox)
        self.assertTrue(any("must be a string" in e for e in errors))

    def test_wildcard_allowlist_is_rejected(self):
        data = {
            "id": TASK,
            "status": "READY",
            "base_branch": "bridge/work-bridge-bootstrap",
            "allowed_paths": ["tools/work_bridge/**"],
        }
        wildcard_inbox = "<!-- WB_TASK_V1\n" + json.dumps(data) + "\n-->\n"
        errors = self.check(inbox=wildcard_inbox)
        self.assertTrue(any("Unsafe allowed_paths" in e for e in errors))

    def test_duplicate_json_manifest_key_is_rejected(self):
        duplicate = (
            '<!-- WB_TASK_V1 {"id":"WB-004","id":"WB-003","status":"READY",'
            '"base_branch":"bridge/work-bridge-bootstrap","allowed_paths":["'
            + REPORT_PATH + '"]} -->'
        )
        errors = self.check(inbox=duplicate)
        self.assertTrue(any("Duplicate JSON key" in e for e in errors))

    def test_wrong_branch_task_id_rejected(self):
        errors = self.check(head_branch="bridge/task-WB-099-wrong")
        self.assertTrue(any("does not match" in e for e in errors))

    def test_missing_report_rejected(self):
        errors = self.check(changed_paths=["tools/work_bridge/validate.py"])
        self.assertTrue(any("Required task report missing" in e for e in errors))

    def test_fork_pr_rejected(self):
        errors = self.check(head_repo="other/fork")
        self.assertTrue(any("Cross-repository" in e for e in errors))

    def test_delete_rename_or_type_change_rejected(self):
        for status in ("D", "R100", "T", "C100"):
            with self.subTest(status=status):
                with self.assertRaises(PolicyError):
                    changed_paths_from_status(f"{status}\t{REPORT_PATH}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
