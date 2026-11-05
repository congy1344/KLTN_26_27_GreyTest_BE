"""Run test-only offline benchmark; excludes local dotenv and LLM credentials."""
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
label, *extra = sys.argv[1:]
assert re.fullmatch(r"[a-zA-Z0-9-]+", label)
env = os.environ.copy()
for name in ("LLM_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY", "GEMINI_API_KEY"):
    env.pop(name, None)
source = Path("src/main/resources/application.yml").read_text(encoding="utf-8")
for name in ("SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
    env[name] = re.search(r"\$\{" + name + r":([^}]*)\}", source).group(1)
env.update(SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/greytest_incremental_test_20261024",
           LLM_PROVIDER="mock", LLM_MODEL="offline-deterministic", LLM_API_KEY="",
           JAVA_TOOL_OPTIONS="-Xms16m -Xmx384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC",
           MAVEN_OPTS="-Xms16m -Xmx256m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC")
empty = Path("target/phase3-6-offline-empty.properties"); empty.parent.mkdir(exist_ok=True)
empty.write_bytes(b"")
args = ["mvn.cmd", "-o", "-B", "-ntp", "-Dspring.config.import=optional:file:" + empty.as_posix(),
        "-Dllm.provider=mock", "-Dllm.api-key=", "-Dgreytest.ai-context-log-enabled=false",
        "-Dgreytest.ai-context-log-console-enabled=false",
        "-Dgreytest.source-update.single-statement-lease-check-enabled=false",
        "-Dgreytest.source-update.jdbc-read-optimization-enabled=true",
        "-Dgreytest.source-update.overlay-read-bundle-enabled=true",
        "-Dgreytest.source-update.lombok-directory-reuse-enabled=true",
        "-DargLine=-Xms16m -Xmx512m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC", *extra, "test"]
assert env["LLM_PROVIDER"] == "mock" and not env["LLM_API_KEY"]
started = time.monotonic()
path = Path("target") / (label + ".log")
with path.open("wb") as output:
    result = subprocess.run(args, env=env, stdout=output, stderr=subprocess.STDOUT)
print("Exit", result.returncode, "seconds", round(time.monotonic() - started, 3), "log", path)
log = path.read_text(encoding="utf-8", errors="replace")
print("\n".join(line for line in log.splitlines() if "Tests run:" in line or "BUILD SUCCESS" in line or "BUILD FAILURE" in line)[-2500:])
raise SystemExit(result.returncode)
