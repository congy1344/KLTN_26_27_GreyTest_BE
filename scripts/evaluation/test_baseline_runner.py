"""Offline checks: no provider or project Java execution."""
import argparse
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import baseline_runner as runner


def write(path, value):
    path.write_bytes(runner.encoded(value))


class BaselineRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary_root = runner.BACKEND / "target"
        temporary_root.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=temporary_root)
        self.root = Path(self.temporary.name)
        self.checkout = self.root / "checkout"
        self.checkout.mkdir()
        self.name = "src/main/java/example/AccountService.java"
        self.source = self.checkout / self.name
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(b"package example; public class AccountService {}\n")
        for arguments in (("init",), ("add", self.name),
                          ("-c", "user.name=Offline Test", "-c", "user.email=offline@example.invalid",
                           "-c", "commit.gpgsign=false", "commit", "-m", "frozen")):
            subprocess.run(["git", "-C", str(self.checkout), *arguments], check=True, capture_output=True)
        commit = runner.git(self.checkout, "rev-parse", "HEAD").decode().strip()
        self.source_hash = runner.digest(self.source.read_bytes())
        self.inventory = self.root / "inventory.json"
        write(self.inventory, [{"path": self.name, "sha256": self.source_hash, "comparison": "RAW_BYTES"}])
        self.dataset = {"id": "offline", "commit": commit, "analysis_scope": self.name,
                        "scope_sha256": self.source_hash, "source_path": str(self.checkout),
                        "blob_inventory": str(self.inventory), "provenance_status": "FROZEN_SOURCE",
                        "ready_for_paid_runs": True, "build_status": "PASS", "oracle_status": "HUMAN_REVIEW_VERIFIED"}
        self.datasets = self.root / "datasets.json"
        write(self.datasets, {"datasets": [self.dataset]})
        self.build = self.root / "build.txt"
        self.build.write_text("OFFLINE TEST FIXTURE ONLY; NOT AN EXPERIMENT", encoding="utf-8")
        self.snapshot = self.root / "snapshot.json"
        self.spec = {"dataset_id": "offline", "commit": commit, "analysis_scope": self.name,
                     "files": [{"path": self.name, "sha256": self.source_hash}],
                     "build": {"status": "PASS", "evidence_file": "build.txt", "evidence_sha256": runner.digest(self.build.read_bytes())}}
        write(self.snapshot, self.spec)
        self.review = self.root / "review.txt"
        self.review.write_text("MOCK APPROVAL FOR SELF-CHECK ONLY", encoding="utf-8")
        self.oracle = self.root / "oracle.json"
        self.rules = {"dataset_id": "offline", "commit": commit, "analysis_scope": self.name,
                      "source_snapshot_sha256": runner.digest(self.snapshot.read_bytes()),
                      "human_review_verified": True, "reviewer": "SELF_CHECK_NOT_HUMAN",
                      "reviewed_at": "OFFLINE", "independent_source": "offline fixture",
                      "review_evidence_file": "review.txt", "review_evidence_sha256": runner.digest(self.review.read_bytes()),
                      "rules": [{"id": "ORACLE-SENTINEL", "description": "independent expected behavior"}]}
        write(self.oracle, self.rules)

    def tearDown(self):
        self.temporary.cleanup()

    def arguments(self, method="llm-only", live=False):
        return argparse.Namespace(datasets=self.datasets, dataset="offline", method=method,
                                  source_snapshot=self.snapshot, approved_br=self.oracle if method == "prompt-based" else None,
                                  evaluation_oracle=self.oracle if live else None, live=live, output=self.root / "run")

    def response(self, usage=True):
        result = {"cases": [{"id": "TC1", "test_type": "Happy Path", "description": "test", "preconditions": [],
                             "test_data": {}, "expected_result": "success", "priority": "High"}],
                  "unit_tests": [{"file_name": "AccountServiceTest.java", "code": "class AccountServiceTest {}"}]}
        root = {"choices": [{"message": {"content": json.dumps(result)}}]}
        if usage:
            root["usage"] = {"prompt_tokens": 4, "completion_tokens": 6, "total_tokens": 10}
        return runner.encoded(root)

    def test_dry_run_never_reads_config_or_calls_provider(self):
        with patch.object(runner, "live_config", side_effect=AssertionError("read env")), \
                patch.object(runner, "call_provider", side_effect=AssertionError("network")):
            result = runner.run(self.arguments())
        prompt = (self.root / "run/prompt.txt").read_text(encoding="utf-8")
        self.assertNotIn("ORACLE-SENTINEL", prompt)
        self.assertNotIn("Approved independent business rules", prompt)
        self.assertIn("package example", prompt)
        self.assertEqual(0, result["actual_calls"])
        self.assertIsNone(result["tokens"]["total_tokens"])
        self.assertIsNone(result["configured_options"])

    def test_prompt_based_requires_binding_and_approval(self):
        runner.run(self.arguments("prompt-based"))
        self.assertIn("ORACLE-SENTINEL", (self.root / "run/prompt.txt").read_text())
        for key, value in (("human_review_verified", False), ("commit", "wrong"),
                           ("source_snapshot_sha256", "wrong"), ("review_evidence_sha256", "wrong")):
            changed = {**self.rules, key: value}
            write(self.oracle, changed)
            with self.assertRaises(ValueError):
                runner.approved_rules(self.oracle, self.dataset, runner.digest(self.snapshot.read_bytes()))

    def test_llm_only_rejects_br_and_fresh_directory_required(self):
        args = self.arguments()
        args.approved_br = self.oracle
        with self.assertRaises(ValueError):
            runner.run(args)
        args.approved_br = None
        runner.run(args)
        with self.assertRaises(FileExistsError):
            runner.run(args)

    def test_source_guards_and_git_binding(self):
        for name in ("../outside.java", ".env", "src/test/java/Test.java", ".git/src/main/java/Secret.java",
                     "C:/src/main/java/Secret.java", "src/main/java/Bad\nName.java"):
            with self.assertRaises(ValueError):
                runner.checked_source_path(self.checkout, name)
        self.source.write_text("changed", encoding="utf-8")
        with self.assertRaises(ValueError):
            runner.source_context(self.dataset, self.spec)
        with self.assertRaises(ValueError):
            runner.source_context(self.dataset, {**self.spec, "commit": "wrong"})

    def test_duplicate_and_missing_scope_rejected(self):
        for files in ([], self.spec["files"] * 2):
            with self.assertRaises(ValueError):
                runner.source_context(self.dataset, {**self.spec, "files": files})

    def test_inventory_hash_is_raw_but_git_comparison_can_normalize_crlf(self):
        self.source.write_bytes(self.source.read_bytes().replace(b"\n", b"\r\n"))
        raw_hash = runner.digest(self.source.read_bytes())
        write(self.inventory, [{"path": self.name, "sha256": raw_hash, "comparison": "CRLF_TO_LF_NORMALIZED"}])
        self.spec["files"][0]["sha256"] = raw_hash
        self.dataset["scope_sha256"] = raw_hash
        sources = runner.source_context(self.dataset, self.spec)
        self.assertEqual(raw_hash, sources[0]["sha256"])
        self.assertIn("\r\n", sources[0]["text"])

    def test_live_gate_before_reading_credentials(self):
        self.dataset["ready_for_paid_runs"] = False
        write(self.datasets, {"datasets": [self.dataset]})
        with patch.object(runner, "live_config", side_effect=AssertionError("env read")):
            with self.assertRaises(ValueError):
                runner.run(self.arguments(live=True))
        self.assertFalse((self.root / "run").exists())

    def mock_live(self, raw, error=None):
        options = {"model": "OFFLINE", "temperature": .3, "max_tokens": 16384, "json_mode": True, "timeout_seconds": 60}
        with patch.object(runner, "live_config", return_value=("https://example.invalid/v1/chat/completions", "NEVER_STORE_KEY", options)), \
                patch.object(runner, "call_provider", return_value=(500 if error else 200, raw, error)) as provider:
            result = runner.run(self.arguments(live=True))
            self.assertEqual(1, provider.call_count)
            self.assertNotIn("ORACLE-SENTINEL", provider.call_args.args[0])
        for path in (self.root / "run").iterdir():
            self.assertNotIn(b"NEVER_STORE_KEY", path.read_bytes())
        self.assertEqual(raw, (self.root / "run/response.raw").read_bytes())
        self.assertEqual(1, result["actual_calls"])
        self.assertGreaterEqual(result["wall_clock_seconds"], 0)
        return result

    def test_success_is_unvalidated_not_pass(self):
        result = self.mock_live(self.response())
        self.assertEqual("GENERATED_UNVALIDATED", result["status"])
        self.assertEqual("PROVIDER", result["tokens"]["source"])
        self.assertEqual(10, result["tokens"]["total_tokens"])
        self.assertEqual("NOT_EXECUTED", result["compilation"])

    def test_missing_usage_is_unknown(self):
        result = self.mock_live(self.response(False))
        self.assertEqual("UNKNOWN", result["tokens"]["source"])
        self.assertIsNone(result["tokens"]["total_tokens"])

    def test_partial_usage_preserves_missing_and_actual_zero(self):
        usage = runner.token_usage(b'{"usage":{"total_tokens":10,"prompt_tokens":0}}')
        self.assertEqual("PROVIDER", usage["source"])
        self.assertEqual(10, usage["total_tokens"])
        self.assertEqual(0, usage["prompt_tokens"])
        self.assertIsNone(usage["completion_tokens"])

    def test_provider_secret_echo_is_redacted(self):
        options = {"model": "OFFLINE", "temperature": .3, "max_tokens": 16384, "json_mode": True, "timeout_seconds": 60}
        with patch.object(runner, "live_config", return_value=("https://example.invalid", "SECRET_ECHO", options)), \
                patch.object(runner, "call_provider", return_value=(500, b'{"error":"SECRET_ECHO"}', "HTTP_ERROR")):
            result = runner.run(self.arguments(live=True))
        self.assertTrue(result["response_redacted"])
        self.assertEqual(b'{"error":"[REDACTED]"}', (self.root / "run/response.raw").read_bytes())

    def test_failure_retained(self):
        result = self.mock_live(b'{"error":"provider failure"}', "HTTP_ERROR")
        self.assertEqual("HTTP_ERROR", result["status"])
        self.assertFalse((self.root / "run/generated.json").exists())

    def test_malformed_retained(self):
        result = self.mock_live(b"not JSON")
        self.assertEqual("MALFORMED_OUTPUT", result["status"])
        self.assertIsNone(result["tokens"]["total_tokens"])

    def test_incomplete_schema_rejected(self):
        with self.assertRaises(ValueError):
            runner.parse_response(runner.encoded({"output_text": '{"cases":[],"unit_tests":[]}'}))


if __name__ == "__main__":
    unittest.main()
