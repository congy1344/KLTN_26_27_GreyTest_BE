"""Cài cấu hình GreyTest lần đầu từ đúng commit đã publish; không build source trên EC2."""

import argparse
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import urllib.request


def settings(site, backend_image, frontend_image):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]+\.[A-Za-z]{2,63}", site):
        raise ValueError("Use a hostname without https://, port or path")
    pattern = r"([0-9]{{12}})\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/greytest/{}:sha-([0-9a-f]{{40}})"
    backend = re.fullmatch(pattern.format("backend"), backend_image)
    frontend = re.fullmatch(pattern.format("frontend"), frontend_image)
    if not backend or not frontend or backend.group(1, 2) != frontend.group(1, 2):
        raise ValueError("Both images must use full commit SHAs in the same account and region")
    return backend.group(1, 2, 3)


def make_env(template, site, backend_image, frontend_image):
    values = {
        "SITE_ADDRESS": site, "RELEASE_TAG": "ci-bootstrap", "LLM_PROVIDER": "mock",
        "POSTGRES_PASSWORD": secrets.token_hex(32), "GREYTEST_AUTH_TOKEN_SECRET": secrets.token_hex(32),
        "BACKEND_IMAGE": backend_image, "FRONTEND_IMAGE": frontend_image,
    }
    for key, value in values.items():
        pattern = rf"(?m)^{key}=.*$"
        if len(re.findall(pattern, template)) > 1:
            raise ValueError(f"Duplicate {key}")
        if re.search(pattern, template):
            template = re.sub(pattern, f"{key}={value}", template)
        else:
            template = template.rstrip('\n') + f"\n{key}={value}\n"
    return template


def bootstrap(site, backend_image, frontend_image):
    account, region, commit = settings(site, backend_image, frontend_image)
    if os.geteuid() != 0:
        raise PermissionError("Run bootstrap with sudo")
    base = Path("/opt/greytest")
    if (base / "config/.env").exists():
        raise FileExistsError("Existing .env found; inspect the existing installation instead of overwriting secrets")
    for command in (["docker", "compose", "version"], ["aws", "--version"]):
        subprocess.run(command, check=True)
    remote = f"https://raw.githubusercontent.com/congy1344/KLTN_26_27_GreyTest_BE/{commit}/"
    files = {
        "docker-compose.aws.yml": "backend/docker-compose.aws.yml",
        "scripts/deployment/deploy-image.sh": "bin/deploy-image.sh",
        "scripts/deployment/smoke-aws.py": "backend/smoke-aws.py",
        ".env.aws.example": "config/.env",
    }
    contents = {}
    for source, target in files.items():
        with urllib.request.urlopen(remote + source, timeout=60) as response:
            contents[target] = response.read().decode("utf-8")
    contents["config/.env"] = make_env(contents["config/.env"], site, backend_image, frontend_image)
    contents["config/deploy.conf"] = f"AWS_ACCOUNT_ID={account}\nAWS_REGION={region}\n"
    os.umask(0o077)
    for directory in ("backend", "bin", "config", "backups"):
        (base / directory).mkdir(parents=True, exist_ok=True)
        (base / directory).chmod(0o700 if directory in ("config", "backups") else 0o755)
    for target, text in contents.items():
        destination = base / target
        destination.write_text(text)
        destination.chmod(0o600 if target.startswith("config/") else 0o755 if target.endswith(".sh") else 0o644)
    compose = ["docker", "compose", "-p", "greytest-aws", "--env-file", str(base / "config/.env"),
               "-f", str(base / "backend/docker-compose.aws.yml")]
    subprocess.run([*compose, "config", "--quiet"], check=True)
    with tempfile.TemporaryDirectory(prefix="greytest-ecr-") as credentials:
        environment = dict(os.environ, DOCKER_CONFIG=credentials)
        login = subprocess.run(["aws", "ecr", "get-login-password", "--region", region],
                               capture_output=True, text=True, check=True)
        subprocess.run(["docker", "login", "--username", "AWS", "--password-stdin",
                        f"{account}.dkr.ecr.{region}.amazonaws.com"],
                       input=login.stdout, text=True, env=environment, check=True)
        subprocess.run([*compose, "pull"], env=environment, check=True)
        subprocess.run([*compose, "up", "-d", "--no-build", "--pull", "never", "--wait",
                        "--wait-timeout", "300"], env=environment, check=True)
    subprocess.run(["python3", str(base / "backend/smoke-aws.py"), f"https://{site}"], check=True)
    print("Bootstrap passed. Enable AWS_DEPLOY_ENABLED=true in both repositories after checking the website.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", required=True)
    parser.add_argument("--backend-image", required=True)
    parser.add_argument("--frontend-image", required=True)
    arguments = parser.parse_args()
    bootstrap(arguments.site, arguments.backend_image, arguments.frontend_image)
