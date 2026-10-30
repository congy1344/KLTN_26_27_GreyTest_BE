"""Gửi deployment qua SSM và chỉ báo thành công sau khi lệnh trên EC2 hoàn tất."""

import json
import os
import re
import shlex
import subprocess
import sys
import time

DELIVERY_SECONDS = 60
EXECUTION_SECONDS = 1800
WAIT_SECONDS = 1920


def aws(*arguments):
    return subprocess.run(
        ["aws", "--no-cli-pager", *arguments, "--output", "json"],
        capture_output=True, text=True, timeout=60, check=False,
    )


def deploy(instance, service, image):
    if not re.fullmatch(r"i-[0-9a-f]{8,17}", instance):
        raise ValueError("AWS_INSTANCE_ID must be an EC2 instance ID")
    if service not in ("backend", "web") or not re.fullmatch(
        r"[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com/greytest/"
        r"(?:backend|frontend):sha-[0-9a-f]{40}", image
    ):
        raise ValueError("Invalid service or immutable ECR image")
    command = shlex.join(["/opt/greytest/bin/deploy-image.sh", service, image])
    request = {
        "DocumentName": "AWS-RunShellScript", "InstanceIds": [instance],
        "Parameters": {"commands": [command], "executionTimeout": [str(EXECUTION_SECONDS)]},
        "TimeoutSeconds": DELIVERY_SECONDS, "Comment": "GreyTest verified main deployment",
    }
    sent = aws("ssm", "send-command", "--cli-input-json", json.dumps(request))
    if sent.returncode:
        raise RuntimeError(sent.stderr)
    command_id = json.loads(sent.stdout)["Command"]["CommandId"]
    print(f"SSM command: {command_id}", flush=True)
    deadline = time.monotonic() + WAIT_SECONDS
    while time.monotonic() < deadline:
        result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                     "--instance-id", instance)
        if result.returncode:
            if "InvocationDoesNotExist" not in result.stderr:
                raise RuntimeError(result.stderr)
        else:
            invocation = json.loads(result.stdout)
            status = invocation["Status"]
            if status not in ("Pending", "InProgress", "Delayed"):
                print(invocation.get("StandardOutputContent", ""))
                print(invocation.get("StandardErrorContent", ""), file=sys.stderr)
                if status != "Success" or invocation.get("ResponseCode") != 0:
                    raise RuntimeError(f"Deployment failed: {status}")
                return
        time.sleep(5)
    raise TimeoutError(f"SSM command {command_id} has not finished; inspect it before retrying")


if __name__ == "__main__":
    deploy(os.environ["AWS_INSTANCE_ID"], os.environ["DEPLOY_SERVICE"], os.environ["DEPLOY_IMAGE"])
