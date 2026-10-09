import tempfile
import json
import shutil
import unittest
from pathlib import Path
from collect import collect, modification, percent, stability, stage_times, tokens, read
from oracle_pilot import pilot


class CollectorTest(unittest.TestCase):
    def test_edit_add_remove_are_separate(self):
        raw = [{"evaluation_id": "1", "test_data": {"amount": 500}}, {"evaluation_id": "2"}]
        snapshot = [{"evaluation_id": "1", "test_data": {"amount": 100}, "status": "APPROVED"}, {"evaluation_id": "3", "status": "PENDING"}]
        self.assertEqual(modification(raw, snapshot), {"generated": 2, "edited": 1, "percent": 50, "added": 1, "removed": 1, "not_approved": 1})
        self.assertIsNone(percent(0, 0))
        with self.assertRaises(ValueError):
            modification(raw + [raw[0]], snapshot)

    def test_missing_tokens_and_estimates(self):
        self.assertIsNone(tokens([], 3)["total_tokens"])
        self.assertEqual(tokens([{"usageSource": "ESTIMATED", "totalTokens": 7}], 1)["estimated_known_tokens"], 7)
        self.assertIsNone(tokens([{"usageSource": "PROVIDER"}], 1)["total_tokens"])

    def test_duplicate_stability_one_to_one_same_type(self):
        case = {"description": "Transfer succeeds with equal balance", "test_type": "BOUNDARY"}
        self.assertEqual(stability([case, case], [case])["percent"], 50)
        self.assertEqual(stability([case], [{**case, "test_type": "EXCEPTION"}])["matched"], 0)
        self.assertEqual(stability([case], [{**case, "description": " TRANSFER succeeds with equal balance!"}])["percent"], 100)
        self.assertIsNone(stability([], [])["percent"])

    def test_wall_clock_is_not_call_latency(self):
        self.assertEqual(stage_times([{"stage": "TEST_CASE", "status": "COMPLETED", "wall_clock_seconds": 2, "summed_call_latency_seconds": 20}])[0]["wall_clock_seconds"], 2)
        with self.assertRaises(ValueError):
            stage_times([{"stage": "TEST_CASE", "status": "FAILED", "wall_clock_seconds": -1}])

    def test_frozen_oracle_import(self):
        source = Path(__file__).resolve().parents[3] / "outputs/approved-br-oracle-2026-10-08"
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as folder:
            destination = Path(folder) / "pilot"
            result = pilot(source, destination)
            self.assertEqual(result["case_edit_audit"]["percent"], 12.5)
            self.assertEqual(result["case_edit_audit"]["removed"], 7)
            self.assertIsNone(result["umr"])
            self.assertEqual(result["requirement_coverage"]["percent"], 100)
            self.assertIsNone(result["tokens"]["total_tokens"])
            self.assertIsNone(result["stability"])
            self.assertTrue((destination / "collected/results.csv").is_file())
            with self.assertRaises(FileExistsError):
                pilot(source, destination)

    def test_archive_replays_without_original_input(self):
        source = Path(__file__).resolve().parents[3] / "outputs/approved-br-oracle-2026-10-08"
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as folder:
            destination = Path(folder) / "pilot"
            result = pilot(source, destination)
            archived = Path(folder) / "standalone"
            shutil.copytree(destination / "collected", archived)
            shutil.rmtree(destination)
            replayed = collect(archived / "bundle.json", Path(folder) / "replayed")
            for key in ("covered_requirement_ids", "human_hitl_verified", "requirement_coverage", "case_edit_audit", "umr", "tokens", "stages", "stability"):
                self.assertEqual(result[key], replayed[key])
            self.assertFalse(read(archived / "bundle.json")["human_hitl_verified"])
            self.assertEqual(read(archived / "bundle.json")["covered_requirement_ids"], ["EQ-001"])

    def test_setup_failure_with_sut_frame_is_not_detection(self):
        source = Path(__file__).resolve().parents[3] / "outputs/approved-br-oracle-2026-10-08"
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent) as folder:
            copied = Path(folder) / "source"
            shutil.copytree(source, copied)
            execution_path = copied / "direct/execution.json"
            execution = read(execution_path)
            execution["faulty"]["failureMethods"] = ["setUp"]
            execution["faulty"]["failureFrames"] = [["fixture.TransferServiceTest#setUp", "fixture.TransferService#transfer"]]
            execution_path.write_text(json.dumps(execution), encoding="utf-8")
            result = pilot(copied, Path(folder) / "pilot")
            self.assertEqual(result["status"], "Fail")
            self.assertEqual(result["requirement_coverage"]["covered"], 0)


if __name__ == "__main__":
    unittest.main()
