"""Unit test cho trình chạy thực nghiệm GreyTest vs LLM-only (chỉ phần logic thuần, không gọi mạng/Maven)."""
import io
import sys
import tempfile
import unittest
import urllib.error
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import experiment  # noqa: E402
from experiment import *  # noqa: E402,F401,F403


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        r = self.root / "project"
        write(r / "pom.xml", "<project><properties><java.version>17</java.version></properties></project>")
        write(r / "src/main/java/a/OrderService.java", "package a;\n@Service\npublic class OrderService {}\n")
        write(r / "src/main/java/a/Repo.java", "package a;\n@Service\npublic interface Repo {}\n")
        write(r / "src/main/java/a/Bom.java", "﻿package a;\npublic class Bom {}\n")
        write(r / "src/main/resources/application.yml", "server.port: 8080\n")
        write(r / "src/main/resources/static/app.js", "alert(1)\n")
        write(r / "src/test/java/a/XTest.java", "class XTest {}\n")
        write(r / "target/x.java", "class X {}\n")
        self.project = r

    def tearDown(self):
        self._tmp.cleanup()


class SourcesTest(Base):
    def test_collect_sources_filters_and_hashes(self):
        sources = collect_sources(self.project)
        self.assertEqual([s["path"] for s in sources],
                         ["pom.xml", "src/main/java/a/Bom.java", "src/main/java/a/OrderService.java",
                          "src/main/java/a/Repo.java", "src/main/resources/application.yml"])
        self.assertFalse(sources[1]["text"].startswith("﻿"))
        self.assertEqual(len(sources[0]["sha256"]), 64)

    def test_collect_sources_survives_non_utf8(self):
        (self.project / "src/main/java/a/Latin.java").write_bytes(b"package a; // caf\xe9\nclass Latin {}\n")
        latin = [s for s in collect_sources(self.project) if s["path"].endswith("Latin.java")][0]
        self.assertIsNotNone(latin["warning"])

    def test_collect_sources_skips_large_resources_but_never_java(self):
        write(self.project / "src/main/resources/dump.sql", "x" * 100_001)
        write(self.project / "src/main/java/a/Big.java", "class Big {}" + " " * 200_000)
        paths = [s["path"] for s in collect_sources(self.project)]
        self.assertNotIn("src/main/resources/dump.sql", paths)
        self.assertIn("src/main/java/a/Big.java", paths)
        self.assertEqual(skipped_resources(self.project), ["src/main/resources/dump.sql"])

    def test_java_version_prefers_release(self):
        self.assertEqual(java_version("<java.version>17</java.version>"), "17")
        self.assertEqual(java_version("<maven.compiler.release>21</maven.compiler.release>"
                                      "<java.version>17</java.version>"), "21")
        self.assertIsNone(java_version("<project/>"))

    def test_service_scope_skips_interfaces(self):
        self.assertEqual(service_scope(collect_sources(self.project)), ["a.OrderService"])

    def test_service_scope_handles_annotation_arguments(self):
        write(self.project / "src/main/java/b/PayServiceImpl.java",
              'package b;\n@Service("pay")\n@Transactional\npublic class PayServiceImpl implements Pay {}\n')
        self.assertEqual(service_scope(collect_sources(self.project)), ["a.OrderService", "b.PayServiceImpl"])

    def test_new_run_dir_refuses_existing(self):
        new_run_dir(self.root / "run")
        with self.assertRaises(FileExistsError):
            new_run_dir(self.root / "run")


class LlmOnlyTest(Base):
    def test_load_env_parses_and_strips_quotes(self):
        write(self.root / ".env", '# c\n\nLLM_MODEL="gpt-6-luna"\nLLM_TEMPERATURE=0.3\n')
        self.assertEqual(load_env(self.root / ".env"), {"LLM_MODEL": "gpt-6-luna", "LLM_TEMPERATURE": "0.3"})

    def test_prompt_contains_sources_and_java_but_no_test_hints(self):
        p = build_prompt([{"path": "pom.xml", "text": "<p/>"},
                          {"path": "src/main/java/a/S.java", "text": "class S{}"}], "17")
        self.assertIn("### FILE: src/main/java/a/S.java", p)
        self.assertIn("Java 17", p)
        self.assertIn("@Service", p)
        for leaked in ("Business Rule", "Test Plan", "Test Case", "GreyTest"):
            self.assertNotIn(leaked, p)

    def test_prompt_without_java_has_no_target_line(self):
        self.assertNotIn("Target Java", build_prompt([{"path": "pom.xml", "text": "<p/>"}], None))

    def test_request_body_luna_uses_completion_tokens(self):
        b = request_body("x", "gpt-6-luna", 0.3, 64000)
        self.assertEqual((b["max_completion_tokens"], b["reasoning_effort"], b["temperature"]), (64000, "none", 0.3))
        self.assertNotIn("response_format", b)
        self.assertEqual(request_body("x", "gpt-4o-mini", 0.3, 100)["max_tokens"], 100)

    def test_extract_uses_path_header_or_package(self):
        text = ("```java\n// src/test/java/a/STest.java\npackage a;\nclass STest {}\n```\n"
                "```java\npackage b;\npublic class TTest {}\n```\n")
        files, errors = extract_test_files(text)
        self.assertEqual(sorted(files), ["src/test/java/a/STest.java", "src/test/java/b/TTest.java"])
        self.assertEqual(errors, [])

    def test_extract_drops_unclosed_last_block(self):
        files, errors = extract_test_files("```java\npackage a;\nclass ATest {}\n```\n```java\npackage a;\nclass BTe")
        self.assertEqual(list(files), ["src/test/java/a/ATest.java"])
        self.assertEqual(len(errors), 1)

    def test_extract_duplicate_path_keeps_last(self):
        block = "```java\npackage a;\nclass ATest { int v = %d; }\n```\n"
        files, errors = extract_test_files(block % 1 + block % 2)
        self.assertIn("v = 2", files["src/test/java/a/ATest.java"])
        self.assertEqual(len(errors), 1)

    def test_retry_only_on_transport_errors(self):
        calls = []

        def flaky(request, timeout):
            calls.append(1)
            if len(calls) < 3:
                raise urllib.error.URLError("reset")
            return io.BytesIO(b'{"ok": 1}')

        with mock.patch.object(experiment, "RETRY_DELAY_SECONDS", 0):
            response, log = post_with_retry("http://x", {}, {}, 5, opener=flaky)
        self.assertEqual((response, len(log)), ({"ok": 1}, 3))

    def test_llm_only_exports_request_wait_without_retry_delay(self):
        run = self.root / "run"
        run.mkdir()
        env = {"LLM_PROVIDER": "openai", "LLM_MODEL": "m", "LLM_API_KEY": "test-key",
               "LLM_OPENAI_URL": "http://unused"}
        attempts = [{"seconds": 0.5, "error": "timeout"}, {"seconds": 1.25, "error": None}]
        with mock.patch.object(experiment, "post_with_retry", return_value=({"choices": []}, attempts)), \
                mock.patch.object(experiment.time, "monotonic", side_effect=[10, 20]):
            result = experiment.generate_llm_only(self.project, run, env, 1000, 30)
        self.assertEqual(result["ai_wait_seconds"], 1.75)
        self.assertEqual(result["generation_seconds"], 10)
        self.assertEqual(result["retries"], 1)

    def test_extract_rejects_traversal_in_llm_path_header(self):
        files, _ = extract_test_files("```java\n// src/test/java/../../../x/EvilTest.java\npackage a;\nclass EvilTest {}\n```\n")
        self.assertEqual(list(files), ["src/test/java/a/EvilTest.java"])

    def test_auth_error_body_is_not_logged(self):
        def unauthorized(request, timeout):
            raise urllib.error.HTTPError("http://x", 401, "unauthorized", {},
                                         io.BytesIO(b'{"error": "Incorrect API key provided: sk-abc***wxyz"}'))

        with self.assertRaises(RuntimeError) as caught:
            post_with_retry("http://x", {}, {}, 5, opener=unauthorized)
        self.assertNotIn("sk-", str(caught.exception))

    def test_retry_does_not_repeat_http_400(self):
        calls = []

        def bad(request, timeout):
            calls.append(1)
            raise urllib.error.HTTPError("http://x", 400, "bad", {}, io.BytesIO(b"{}"))

        with self.assertRaises(RuntimeError):
            post_with_retry("http://x", {}, {}, 5, opener=bad)
        self.assertEqual(len(calls), 1)


class GreyTestDriverTest(Base):
    def test_generate_defaults_to_no_stage_resumes(self):
        with mock.patch.object(experiment, "cmd_generate") as generate:
            experiment.main(["generate", "--project", "p", "--method", "greytest", "--out", "r"])
        self.assertEqual(generate.call_args.args[0].max_resume, 0)

    def test_matrix_inherits_single_pass_default(self):
        with mock.patch.object(experiment, "cmd_matrix") as matrix:
            experiment.main(["matrix", "--projects", "p", "--root", "r"])
        self.assertEqual(matrix.call_args.args[0].max_resume, 0)

    def test_partial_unit_stage_is_not_restarted_in_single_pass(self):
        api = mock.Mock()
        def call(method, path, **kwargs):
            if path.endswith("/analyze"):
                return 200, {"classes": []}
            if "/generate?" in path:
                return 202, {}
            return 200, ([{"id": 1}] if method == "GET" else {})
        api.call.side_effect = call
        progress = [{"status": "COMPLETED", "runMetrics": {"runId": str(i), "llmWaitMillis": 10}}
                    for i in range(3)]
        progress.append({"status": "PARTIAL", "runMetrics": {"runId": "ut", "llmWaitMillis": 20}})
        result = {"stages": [], "resumes": 0}
        with mock.patch.object(experiment, "_poll", side_effect=progress):
            experiment._run_pipeline(api, 1, self.root, [], 0, result, {})
        requests = [c.args[1] for c in api.call.call_args_list if "/generate?" in c.args[1]]
        self.assertEqual(len(requests), 4)
        self.assertTrue(all(p.endswith("resume=false") for p in requests))
        self.assertEqual(result["resumes"], 0)
        self.assertEqual(result["status"], "PARTIAL")
        self.assertTrue((self.root / "UNIT_TEST-attempt-1.json").exists())
        self.assertFalse((self.root / "UNIT_TEST-attempt-2.json").exists())

    def test_should_resume_rules(self):
        self.assertFalse(should_resume([5], "COMPLETED", 0, 3))
        self.assertTrue(should_resume([5], "PARTIAL", 0, 3))
        self.assertFalse(should_resume([5, 8], "PARTIAL", 3, 3))
        self.assertFalse(should_resume([5, 5, 5], "PARTIAL", 2, 3))
        self.assertFalse(should_resume([5, 5], "PARTIAL", 1, 3))
        self.assertFalse(should_resume([5, 4], "PARTIAL", 1, 3))
        self.assertTrue(should_resume([5, 5, 6], "PARTIAL", 2, 5))

    def test_extract_zip_tests_keeps_only_java_under_test_root(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("project/src/test/java/a/STest.java", "package a; class STest {}")
            z.writestr("TTest.java", "package b; class TTest {}")
            z.writestr("run-greytest-coverage.sh", "echo")
        written = extract_zip_tests(buf.getvalue(), self.root / "tests")
        # Helper GreyTestSourceCheck.java / file ở gốc zip không phải test do AI sinh: bỏ qua.
        self.assertEqual(written, ["src/test/java/a/STest.java"])
        self.assertFalse((self.root / "tests/src/test/java/b/TTest.java").exists())

    def test_extract_zip_tests_rejects_path_traversal(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("src/test/java/../../../../evil/ETest.java", "package e; class ETest {}")
        self.assertEqual(extract_zip_tests(buf.getvalue(), self.root / "tests"), [])
        self.assertFalse((self.root / "evil").exists())

    def test_pipeline_totals_uses_last_attempt_per_stage(self):
        # Backend cộng dồn runMetrics của lượt trước vào lượt resume, nên chỉ lấy attempt cuối mỗi stage.
        stages = {"BUSINESS_RULE": [{"recordedCalls": 2, "totalTokens": 100, "inputTokens": 80, "outputTokens": 20,
                                     "elapsedMillis": 1000, "llmWaitMillis": 600},
                                    {"recordedCalls": 5, "totalTokens": 260, "inputTokens": 200, "outputTokens": 60,
                                     "elapsedMillis": 2500, "llmWaitMillis": 1100}],
                  "TEST_PLAN": [{"recordedCalls": 1, "totalTokens": 40, "inputTokens": 30, "outputTokens": 10,
                                 "elapsedMillis": 400, "llmWaitMillis": 200}],
                  "UNIT_TEST": [{"elapsedMillis": 9000, "llmWaitMillis": 1250}]}
        self.assertEqual(pipeline_totals(stages), {"requests": 6, "input_tokens": 230, "output_tokens": 70,
                                                   "total_tokens": 300, "stage_elapsed_millis": 11900,
                                                   "ai_wait_seconds": 2.55})

    def test_pipeline_totals_does_not_infer_ai_wait_from_old_backend(self):
        stages = {"BUSINESS_RULE": [{"llmWaitMillis": 0}], "UNIT_TEST": [{"elapsedMillis": 9000}]}
        self.assertIsNone(pipeline_totals(stages)["ai_wait_seconds"])
        del stages["UNIT_TEST"]
        self.assertEqual(pipeline_totals(stages)["ai_wait_seconds"], 0)

    def test_poll_waits_for_paused_workers_to_finish_ai_requests(self):
        final = {"status": "PAUSED", "busy": False, "runMetrics": {"llmWaitMillis": 1200}}
        api = mock.Mock()
        api.call.side_effect = [(200, {"status": "PAUSED", "busy": True,
                                       "runMetrics": {"llmWaitMillis": 800}}), (200, final), (200, final)]
        with mock.patch.object(experiment.time, "sleep"):
            self.assertEqual(experiment._poll(api, 1, "UNIT_TEST", None), final)

    def test_poll_follows_the_backend_auto_resume_after_a_partial_round(self):
        partial = {"status": "PARTIAL", "busy": False, "runMetrics": {"runId": "r"}}
        final = {"status": "COMPLETED", "busy": False, "runMetrics": {"runId": "r"}}
        api = mock.Mock()
        api.call.side_effect = [(200, partial), (200, {"status": "QUEUED", "busy": True}), (200, final), (200, final)]
        with mock.patch.object(experiment.time, "sleep"):
            self.assertEqual(experiment._poll(api, 1, "TEST_CASE", None), final)

    def test_retry_conflict_repeats_409(self):
        answers = iter([(409, "busy"), (409, "busy"), (200, {"ok": 1})])
        with mock.patch.object(experiment, "CONFLICT_DELAY_SECONDS", 0):
            self.assertEqual(retry_conflict(lambda: next(answers)), (200, {"ok": 1}))

    def test_generate_writes_failed_generation_on_crash(self):
        write(self.root / ".env", "LLM_PROVIDER=openai\nLLM_MODEL=m\n")
        args = mock.Mock(project=str(self.project), method="greytest", out=str(self.root / "run"),
                         env=str(self.root / ".env"), max_output=10, timeout=10, base_url="http://x", max_resume=3)
        with mock.patch.object(experiment, "generate_greytest", side_effect=RuntimeError("TEST_PLAN approve failed")):
            self.assertEqual(cmd_generate(args), 1)
        result = experiment.json.loads((self.root / "run/generation.json").read_text(encoding="utf-8"))
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("approve failed", result["error"])

    def test_generate_refuses_multi_module_project_before_creating_run_dir(self):
        write(self.project / "pom.xml", "<project><modules><module>a</module></modules></project>")
        args = mock.Mock(project=str(self.project), method="llm-only", out=str(self.root / "run"),
                         env=str(self.root / ".env"))
        with self.assertRaises(ValueError):
            cmd_generate(args)
        self.assertFalse((self.root / "run").exists())

    def test_source_zip_matches_collected_sources(self):
        sources = collect_sources(self.project)
        names = zipfile.ZipFile(io.BytesIO(source_zip(sources))).namelist()
        self.assertEqual(sorted(names), [s["path"] for s in sources])


LOG = ("[ERROR] COMPILATION ERROR :\n"
       "[ERROR] /D:/x/sandbox/src/test/java/a/ATest.java:[12,5] cannot find symbol\n"
       "[ERROR] /D:/x/sandbox/src/test/java/a/ATest.java:[14,1] cannot find symbol\n"
       "[ERROR] /D:/x/sandbox/src/main/java/a/S.java:[1,1] boom\n")

JACOCO = """<report name="r">
 <package name="a">
  <class name="a/OrderService"><counter type="LINE" missed="2" covered="8"/><counter type="BRANCH" missed="1" covered="3"/></class>
  <class name="a/OrderService$Inner"><counter type="LINE" missed="1" covered="1"/></class>
  <class name="a/Ctrl"><counter type="LINE" missed="10" covered="0"/></class>
  <counter type="LINE" missed="13" covered="9"/>
 </package>
 <counter type="LINE" missed="13" covered="9"/><counter type="BRANCH" missed="1" covered="3"/>
</report>"""


class MeasureTest(Base):
    def test_failed_test_files_only_test_sources(self):
        self.assertEqual(failed_test_files(LOG, Path("D:/x/sandbox")),
                         {"src/test/java/a/ATest.java": "[12,5] cannot find symbol"})

    def test_parse_jacoco_scope_includes_inner_classes(self):
        r = parse_jacoco(JACOCO, ["a.OrderService", "a.Missing"])
        self.assertEqual((r["line"], r["branch"], r["project_line"], r["project_branch"]),
                         ([9, 3], [3, 1], [9, 13], [3, 1]))
        self.assertEqual(r["missing_classes"], ["a.Missing"])
        self.assertEqual(r["covered_classes"], ["a.OrderService"])

    def test_percent_zero_denominator(self):
        self.assertIsNone(percent(0, 0))
        self.assertEqual(percent(1, 2), 33.33)

    def test_parse_surefire_sums_reports(self):
        d = self.root / "reports"
        write(d / "TEST-a.xml", '<testsuite tests="3" failures="1" errors="0" skipped="0"/>')
        write(d / "TEST-b.xml", '<testsuite tests="2" failures="0" errors="1" skipped="0"/>')
        self.assertEqual(parse_surefire(d), {"tests": 5, "failures": 1, "errors": 1, "skipped": 0})

    def _args(self, run):
        return mock.Mock(project=str(self.project), run=str(run), java_home=str(self.root / "jdk"))

    def test_measure_refuses_existing_sandbox(self):
        run = self.root / "run"
        (run / "sandbox").mkdir(parents=True)
        with mock.patch.object(experiment, "run_maven") as maven:
            self.assertNotEqual(cmd_measure(self._args(run)), 0)
            maven.assert_not_called()

    def test_measure_all_files_fail_gives_compile_error(self):
        run = self.root / "run"
        write(run / "tests/src/test/java/a/ATest.java", "package a; class ATest { broken }")
        write(run / "service-scope.json", '["a.OrderService"]')

        def fake_maven(sandbox, java_home, log_path):
            log = ("[ERROR] COMPILATION ERROR :\n[ERROR] " + (Path(sandbox) / "src/test/java/a/ATest.java").as_posix()
                   + ":[1,25] <identifier> expected\n")
            Path(log_path).write_text(log, encoding="utf-8")
            return log

        with mock.patch.object(experiment, "run_maven", side_effect=fake_maven):
            cmd_measure(self._args(run))
        result = experiment.json.loads((run / "measure.json").read_text(encoding="utf-8"))
        self.assertEqual((result["status"], result["line_pct"], result["files_excluded"]), ("COMPILE_ERROR", None, 1))
        self.assertIsNone(result["tested_services"])
        self.assertTrue((run / "excluded-compile-fail/src/test/java/a/ATest.java").is_file())

    def test_measure_keeps_excluding_beyond_five_rounds(self):
        run = self.root / "run"
        for i in range(7):
            write(run / f"tests/src/test/java/a/T{i}Test.java", f"package a; class T{i}Test {{}}")
        write(run / "service-scope.json", '["a.OrderService"]')

        def one_error_per_round(sandbox, java_home, log_path):
            remaining = sorted((Path(sandbox) / "src/test/java/a").glob("*.java"))
            if len(remaining) == 1:
                return "[INFO] BUILD SUCCESS\n"
            return "[ERROR] COMPILATION ERROR :\n[ERROR] " + remaining[0].as_posix() + ":[1,1] boom\n"

        with mock.patch.object(experiment, "run_maven", side_effect=one_error_per_round):
            cmd_measure(self._args(run))
        result = experiment.json.loads((run / "measure.json").read_text(encoding="utf-8"))
        self.assertEqual((result["rounds"], result["files_excluded"], result["files_compiled"]), (7, 6, 1))
        self.assertEqual(result["status"], "NO_COVERAGE_REPORT")

    def test_measure_without_tests_is_no_tests_not_compile_error(self):
        run = self.root / "run"
        write(run / "service-scope.json", '["a.OrderService"]')
        with mock.patch.object(experiment, "run_maven") as maven:
            cmd_measure(self._args(run))
            maven.assert_not_called()
        result = experiment.json.loads((run / "measure.json").read_text(encoding="utf-8"))
        self.assertEqual((result["status"], result["line_pct"]), ("NO_TESTS", None))

    def test_measure_timeout_is_recorded_not_raised(self):
        run = self.root / "run"
        write(run / "tests/src/test/java/a/ATest.java", "package a; class ATest {}")
        write(run / "service-scope.json", '["a.OrderService"]')
        timeout = experiment.subprocess.TimeoutExpired("mvn", 1)
        with mock.patch.object(experiment, "run_maven", side_effect=timeout):
            cmd_measure(self._args(run))
        result = experiment.json.loads((run / "measure.json").read_text(encoding="utf-8"))
        self.assertEqual((result["status"], result["line_pct"]), ("TIMEOUT", None))


class StatsTest(Base):
    def test_pair_stability_one_to_one(self):
        a = ["OrderService#createorder", "OrderService#cancelorder"]
        b = ["OrderService#createorder", "OrderService#cancelorders", "Pay#x"]
        self.assertAlmostEqual(pair_stability(a, b), 100 * 2 / 3, places=2)
        self.assertIsNone(pair_stability([], []))
        self.assertEqual(pair_stability(["A#x"], []), 0.0)

    def test_pair_stability_does_not_reuse_a_match(self):
        self.assertEqual(pair_stability(["S#create", "S#create"], ["S#create"]), 50.0)

    def test_describe_sd_needs_two(self):
        self.assertIsNone(describe([10.0, None])["sd"])
        d = describe([10.0, 20.0, None])
        self.assertEqual((d["n"], d["mean"], d["median"], d["min"], d["max"]), (2, 15.0, 15.0, 10.0, 20.0))
        self.assertEqual(describe([None])["n"], 0)

    def test_req_coverage_counts_distinct_rules(self):
        write(self.root / "m.csv", "br_id,test\nBR1,A#x\nBR1,A#y\nBR2,B#z\n")
        self.assertEqual(req_coverage(self.root / "m.csv", 4), 50.0)
        self.assertIsNone(req_coverage(self.root / "none.csv", 4))

    def test_test_method_keys_reads_annotations(self):
        write(self.root / "t/src/test/java/a/OrderServiceTest.java",
              "class OrderServiceTest {\n @Test\n void create_Order() {}\n @ParameterizedTest\n"
              " @ValueSource(ints = {1})\n void cancel(int x) {}\n void helper() {}\n}")
        self.assertEqual(test_method_keys(self.root / "t"), ["OrderService#createorder", "OrderService#cancel"])

    def test_test_method_keys_skips_comments_between_annotation_and_method(self):
        # GreyTest chèn "// GreyTest trace: ..." giữa @Test và chữ ký method (thực nghiệm 16/11).
        write(self.root / "t/src/test/java/a/CourseServiceTest.java",
              "class CourseServiceTest {\n @Test\n // GreyTest trace: create | IF-1 (x) | BR-002\n"
              " void testCreate_Saves() {}\n @Test /* note */ void other() {}\n}")
        self.assertEqual(test_method_keys(self.root / "t"), ["CourseService#testcreatesaves", "CourseService#other"])

    def test_stats_writes_runs_and_summary(self):
        for method, line in (("llm-only", 50.0), ("greytest", 90.0)):
            run = self.root / "runs" / method / "run-01"
            write(run / "generation.json", experiment.json.dumps(
                {"method": method, "project": "p", "status": "OK", "total_tokens": 10, "test_files": 1,
                 "ai_wait_seconds": 2.5 if method == "greytest" else 1.2}))
            write(run / "measure.json", experiment.json.dumps(
                {"status": "OK", "tests": 4, "passed": 3, "line_pct": line, "scope_size": 2}))
            write(run / "tests/src/test/java/a/STest.java", "class STest { @Test void x() {} }")
        out = self.root / "stats"
        self.assertEqual(cmd_stats(mock.Mock(runs=str(self.root / "runs"), out=str(out), ground_truth=None)), 0)
        rows = (out / "runs.csv").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(rows), 3)
        self.assertIn("pass_pct", rows[0])
        summary = (out / "summary.csv").read_text(encoding="utf-8")
        self.assertIn("p,greytest,line_pct,1,90.0,,90.0", summary)
        compare = (out / "compare.csv").read_text(encoding="utf-8").splitlines()
        self.assertTrue(compare[0].startswith("project,metric,n_greytest,mean_greytest,n_llm_only,mean_llm_only"))
        self.assertIn("p,line_pct,1,90.0,1,50.0,40.0", "\n".join(compare))
        self.assertIn("p,ai_wait_seconds,1,2.5,1,1.2,1.3", "\n".join(compare))
        self.assertTrue(any(row.startswith("ALL (macro),line_pct") for row in compare))


class SignificanceTest(unittest.TestCase):
    def test_mann_whitney_exact_complete_separation(self):
        u, p = mann_whitney_exact([6, 7, 8, 9, 10], [1, 2, 3, 4, 5])
        self.assertEqual(u, 25.0)
        self.assertAlmostEqual(p, 2 / 252, places=6)

    def test_mann_whitney_exact_identical_groups(self):
        u, p = mann_whitney_exact([1, 2, 3], [1, 2, 3])
        self.assertEqual((u, p), (4.5, 1.0))

    def test_mann_whitney_needs_two_groups(self):
        self.assertEqual(mann_whitney_exact([], [1, 2]), (None, None))

    def test_cliffs_delta(self):
        self.assertEqual(cliffs_delta([3, 4], [1, 2]), 1.0)
        self.assertEqual(cliffs_delta([1, 2], [1, 2]), 0.0)
        self.assertEqual(cliffs_delta([1], [2, 3]), -1.0)

    def test_wilcoxon_signed_rank_exact(self):
        # 6 cặp đều dương: p hai phía = 2 / 2^6.
        self.assertAlmostEqual(wilcoxon_exact([5, 6, 7, 8, 9, 10], [1, 2, 3, 4, 5, 6]), 2 / 64, places=6)
        self.assertEqual(wilcoxon_exact([1, 2], [1, 2]), 1.0)
        self.assertIsNone(wilcoxon_exact([1], []))


class MatrixTest(Base):
    def test_build_order_is_complete_and_deterministic(self):
        order = build_order(["p1", "p2"], ["llm-only", "greytest"], 3, seed=7)
        self.assertEqual(len(order), 12)
        self.assertEqual(order, build_order(["p1", "p2"], ["llm-only", "greytest"], 3, seed=7))
        self.assertEqual({(o["project"], o["method"], o["repeat"]) for o in order},
                         {(p, m, r) for p in ("p1", "p2") for m in ("llm-only", "greytest") for r in (1, 2, 3)})

    def _matrix_args(self, root):
        return mock.Mock(projects=[str(self.project)], methods=["llm-only", "greytest"], repeats=2, seed=1,
                         root=str(root), env=str(self.root / ".env"), max_output=10, timeout=10,
                         base_url="http://x", max_resume=3, java_home="jdk")

    def test_matrix_runs_each_step_once_and_resumes(self):
        calls = []

        def fake_generate(args):
            calls.append(("generate", Path(args.out).name, args.method))
            Path(args.out).mkdir(parents=True)
            write(Path(args.out) / "generation.json", "{}")
            return 0

        def fake_measure(args):
            calls.append(("measure", Path(args.run).name))
            write(Path(args.run) / "measure.json", "{}")
            return 0

        root = self.root / "main"
        with mock.patch.object(experiment, "cmd_generate", side_effect=fake_generate), \
                mock.patch.object(experiment, "cmd_measure", side_effect=fake_measure):
            cmd_matrix(self._matrix_args(root))
            self.assertEqual(len(calls), 8)
            cmd_matrix(self._matrix_args(root))  # Lần hai: mọi bước đã xong, không chạy lại.
        self.assertEqual(len(calls), 8)
        self.assertTrue((root / "order.json").is_file())

    def test_matrix_sets_aside_interrupted_generation(self):
        root = self.root / "main"
        broken = root / "project" / "llm-only" / "run-01"
        broken.mkdir(parents=True)  # Bị ngắt giữa chừng: có thư mục nhưng không có generation.json.

        def fake_generate(args):
            Path(args.out).mkdir(parents=True)
            write(Path(args.out) / "generation.json", "{}")
            return 0

        with mock.patch.object(experiment, "cmd_generate", side_effect=fake_generate), \
                mock.patch.object(experiment, "cmd_measure", return_value=0):
            cmd_matrix(self._matrix_args(root))
        self.assertTrue((broken / "generation.json").is_file())
        self.assertEqual(len(list((root / "interrupted").iterdir())), 1)


if __name__ == "__main__":
    unittest.main()
