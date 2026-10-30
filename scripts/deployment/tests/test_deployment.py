"""Kiểm chứng deployment bằng CLI giả, không kết nối AWS hoặc Docker daemon."""

import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("deploy_ssm", ROOT / ".github/scripts/deploy-ssm.py")
SSM = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SSM)
BOOTSTRAP_SPEC = importlib.util.spec_from_file_location("bootstrap_ci", ROOT / "scripts/deployment/bootstrap-ci.py")
BOOTSTRAP = importlib.util.module_from_spec(BOOTSTRAP_SPEC)
BOOTSTRAP_SPEC.loader.exec_module(BOOTSTRAP)
REGISTRY = "123456789012.dkr.ecr.ap-southeast-2.amazonaws.com"
OLD = "0" * 40
NEW = "1" * 40


class BootstrapTests(unittest.TestCase):
    def test_accepts_the_two_independent_commit_images(self):
        result = BOOTSTRAP.settings("example.duckdns.org", f"{REGISTRY}/greytest/backend:sha-{NEW}",
                                    f"{REGISTRY}/greytest/frontend:sha-{OLD}")
        self.assertEqual(result, ("123456789012", "ap-southeast-2", NEW))

    def test_images_must_belong_to_same_account_and_region(self):
        with self.assertRaises(ValueError):
            BOOTSTRAP.settings("example.duckdns.org", f"{REGISTRY}/greytest/backend:sha-{NEW}",
                               f"999999999999.dkr.ecr.ap-southeast-2.amazonaws.com/greytest/frontend:sha-{NEW}")

    def test_hostname_is_not_a_url_or_shell_command(self):
        for site in ("https://example.com", "example.com/path", "example.com;id"):
            with self.assertRaises(ValueError):
                BOOTSTRAP.settings(site, f"{REGISTRY}/greytest/backend:sha-{NEW}",
                                   f"{REGISTRY}/greytest/frontend:sha-{NEW}")

    def test_env_generates_fresh_secrets_and_preserves_other_settings(self):
        template = "SITE_ADDRESS=placeholder\nPOSTGRES_PASSWORD=\nGREYTEST_AUTH_TOKEN_SECRET=\nLLM_PROVIDER=openai\nJAVA_TOOL_OPTIONS=-Xms128m -Xmx768m\n"
        result = BOOTSTRAP.make_env(template, "example.duckdns.org", "backend-image", "frontend-image")
        values = dict(line.split("=", 1) for line in result.splitlines())
        self.assertRegex(values["POSTGRES_PASSWORD"], r"^[0-9a-f]{64}$")
        self.assertRegex(values["GREYTEST_AUTH_TOKEN_SECRET"], r"^[0-9a-f]{64}$")
        self.assertNotEqual(values["POSTGRES_PASSWORD"], values["GREYTEST_AUTH_TOKEN_SECRET"])
        self.assertEqual(values["LLM_PROVIDER"], "mock")
        self.assertEqual(values["JAVA_TOOL_OPTIONS"], "-Xms128m -Xmx768m")

    def test_existing_installation_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "config").mkdir()
            env = base / "config/.env"
            env.write_text("existing-private-configuration")
            with patch.object(BOOTSTRAP, "Path", return_value=base), \
                 patch.object(BOOTSTRAP.os, "geteuid", return_value=0), \
                 patch.object(BOOTSTRAP.subprocess, "run") as command, \
                 self.assertRaises(FileExistsError):
                BOOTSTRAP.bootstrap("example.duckdns.org", f"{REGISTRY}/greytest/backend:sha-{NEW}",
                                    f"{REGISTRY}/greytest/frontend:sha-{OLD}")
            command.assert_not_called()
            self.assertEqual(env.read_text(), "existing-private-configuration")

    def test_bootstrap_uses_commit_pinned_files_and_protects_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            def download(url, timeout):
                self.assertIn(f"/{NEW}/", url)
                return io.BytesIO(b"SITE_ADDRESS=placeholder\nPOSTGRES_PASSWORD=\nGREYTEST_AUTH_TOKEN_SECRET=\n"
                                  if url.endswith(".env.aws.example") else b"configuration")
            def run(arguments, **kwargs):
                return subprocess.CompletedProcess(arguments, 0, "registry-password" if "get-login-password" in arguments else "", "")
            with patch.object(BOOTSTRAP, "Path", return_value=base), \
                 patch.object(BOOTSTRAP.os, "geteuid", return_value=0), \
                 patch.object(BOOTSTRAP.urllib.request, "urlopen", side_effect=download), \
                 patch.object(BOOTSTRAP.subprocess, "run", side_effect=run) as command:
                BOOTSTRAP.bootstrap("example.duckdns.org", f"{REGISTRY}/greytest/backend:sha-{NEW}",
                                    f"{REGISTRY}/greytest/frontend:sha-{OLD}")
            self.assertEqual((base / "config/.env").stat().st_mode & 0o777, 0o600)
            self.assertEqual((base / "config").stat().st_mode & 0o777, 0o700)
            self.assertIn("BACKEND_IMAGE=", (base / "config/.env").read_text())
            self.assertEqual(command.call_args.args[0][-1], "https://example.duckdns.org")


class SsmTests(unittest.TestCase):
    def test_execution_budget_reserves_time_for_rollback_after_all_phases(self):
        script = (ROOT / "scripts/deployment/deploy-image.sh").read_text()
        values = {key: int(value) for key, value in re.findall(r"(?m)^([a-z_]+)=(\d+)$", script)}
        phases = sum(values[key] for key in ("lock_seconds", "login_seconds", "pull_seconds", "start_seconds", "rollback_seconds"))
        phases += values["health_attempts"] * (values["health_seconds"] + values["health_sleep_seconds"])
        self.assertGreaterEqual(SSM.EXECUTION_SECONDS, phases + 120)
        self.assertGreaterEqual(SSM.WAIT_SECONDS, SSM.DELIVERY_SECONDS + SSM.EXECUTION_SECONDS + 60)

    def response(self, payload=None, error=""):
        return subprocess.CompletedProcess([], bool(error), json.dumps(payload), error)

    def test_waits_for_actual_completion_and_handles_eventual_consistency(self):
        responses = [self.response({"Command": {"CommandId": "command-1"}}),
                     self.response(error="InvocationDoesNotExist"),
                     self.response({"Status": "InProgress"}),
                     self.response({"Status": "Success", "ResponseCode": 0})]
        with patch.object(SSM, "aws", side_effect=responses) as cli, patch.object(SSM.time, "sleep"):
            SSM.deploy("i-0123456789abcdef0", "backend", f"{REGISTRY}/greytest/backend:sha-{NEW}")
        request = json.loads(cli.call_args_list[0].args[-1])
        self.assertEqual(request["InstanceIds"], ["i-0123456789abcdef0"])
        self.assertEqual(request["DocumentName"], "AWS-RunShellScript")
        self.assertEqual(cli.call_count, 4)

    def test_remote_failure_is_not_reported_as_success(self):
        responses = [self.response({"Command": {"CommandId": "command-1"}}),
                     self.response({"Status": "Failed", "ResponseCode": 1})]
        with patch.object(SSM, "aws", side_effect=responses), self.assertRaisesRegex(RuntimeError, "Failed"):
            SSM.deploy("i-0123456789abcdef0", "backend", f"{REGISTRY}/greytest/backend:sha-{NEW}")

    def test_rejects_command_injection_before_sending(self):
        with patch.object(SSM, "aws") as cli, self.assertRaises(ValueError):
            SSM.deploy("i-0123456789abcdef0", "backend", "image; rm -rf /")
        cli.assert_not_called()

    def test_timeout_requires_inspection_instead_of_assuming_success(self):
        with patch.object(SSM, "aws", return_value=self.response({"Command": {"CommandId": "command-1"}})), \
             patch.object(SSM.time, "monotonic", side_effect=[0, SSM.WAIT_SECONDS + 1]), self.assertRaises(TimeoutError):
            SSM.deploy("i-0123456789abcdef0", "backend", f"{REGISTRY}/greytest/backend:sha-{NEW}")


@unittest.skipUnless(os.name == "posix", "Bash deployment runs on Linux")
class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="greytest-deploy-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        (self.base / "config").mkdir()
        (self.base / "backend").mkdir()
        self.env_file = self.base / "config/.env"
        self.original = (f"BACKEND_IMAGE={REGISTRY}/greytest/backend:sha-{OLD}\n"
                         f"FRONTEND_IMAGE={REGISTRY}/greytest/frontend:sha-{OLD}\n"
                         "SITE_ADDRESS=example.duckdns.org\nPOSTGRES_PASSWORD=preserve-this\n")
        self.env_file.write_text(self.original)
        (self.base / "config/deploy.conf").write_text("AWS_ACCOUNT_ID=123456789012\nAWS_REGION=ap-southeast-2\n")
        (self.base / "backend/docker-compose.aws.yml").write_text("services: {}\n")
        tools = self.base / "bin"
        tools.mkdir()
        docker = tools / "docker"
        docker.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ['FAKE_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
if args[0] == 'login':
    sys.stdin.read()
elif args[0] == 'compose':
    if 'ps' in args:
        print('existing-container')
    elif 'up' in args:
        env = pathlib.Path(args[args.index('--env-file') + 1]).read_text()
        if os.environ.get('FAIL_NEW') and 'sha-' + '1' * 40 in env:
            sys.exit(1)
elif args[0] == 'inspect':
    if 'State.Running' in args[2]:
        print('true')
    else:
        print('a' * 64)
elif args[:2] == ['image', 'inspect']:
    print(os.environ.get('NEW_SCHEMA', 'a' * 64))
''')
        docker.chmod(0o755)
        for name, code in {
            "aws": "#!/bin/sh\nprintf 'test-registry-password\\n'\n",
            "curl": "#!/bin/sh\n[ -z \"$FAIL_HEALTH\" ]\n",
            "sleep": "#!/bin/sh\nexit 0\n",
        }.items():
            tool = tools / name
            tool.write_text(code)
            tool.chmod(0o755)
        self.environment = dict(os.environ, GREYTEST_ROOT=str(self.base),
                                PATH=str(tools) + os.pathsep + os.environ["PATH"],
                                FAKE_LOG=str(self.base / "calls.jsonl"))

    def deploy(self, service="backend", **settings):
        repository = "backend" if service == "backend" else "frontend"
        image = settings.pop("IMAGE", f"{REGISTRY}/greytest/{repository}:sha-{NEW}")
        return subprocess.run(["bash", str(ROOT / "scripts/deployment/deploy-image.sh"), service, image],
                              env=dict(self.environment, **settings), capture_output=True, text=True, timeout=20)

    def calls(self):
        path = self.base / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_success_updates_only_target_image_and_preserves_secrets(self):
        result = self.deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.env_file.read_text(), self.original.replace(
            f"backend:sha-{OLD}", f"backend:sha-{NEW}"))
        self.assertEqual(self.env_file.stat().st_mode & 0o777, 0o600)
        up = [call for call in self.calls() if "up" in call]
        self.assertEqual(len(up), 1)
        self.assertIn("--no-deps", up[0])
        self.assertIn("--pull", up[0])
        self.assertFalse(list((self.base / "config").glob(".deploy.*")))

    def test_failed_container_start_restores_previous_configuration(self):
        result = self.deploy(FAIL_NEW="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.env_file.read_text(), self.original)
        self.assertEqual(len([call for call in self.calls() if "up" in call]), 2)

    def test_failed_https_check_also_rolls_back(self):
        result = self.deploy(FAIL_HEALTH="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.env_file.read_text(), self.original)
        self.assertEqual(len([call for call in self.calls() if "up" in call]), 2)

    def test_schema_change_is_blocked_before_changing_containers_or_env(self):
        result = self.deploy(NEW_SCHEMA="b" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("migrations differ", result.stderr)
        self.assertEqual(self.env_file.read_text(), self.original)
        self.assertFalse(any("up" in call for call in self.calls()))

    def test_other_registry_is_rejected_before_any_docker_call(self):
        result = self.deploy(IMAGE=f"999999999999.dkr.ecr.ap-southeast-2.amazonaws.com/greytest/backend:sha-{NEW}")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_frontend_update_does_not_modify_backend(self):
        result = self.deploy(service="web", NEW_SCHEMA="different-schema-is-irrelevant-to-frontend")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.env_file.read_text(), self.original.replace(
            f"frontend:sha-{OLD}", f"frontend:sha-{NEW}"))


if __name__ == "__main__":
    unittest.main()
