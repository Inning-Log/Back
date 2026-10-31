"""Run in AWS CloudShell: python3 scripts/deploy-live-smoke.py.

Build the current clean checkout, register a revision, run one Fargate task.
Does not change recurring schedules. Only prints non-secret verification data.
"""
import json
import argparse
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--activate-planner", action="store_true",
                    help="Verify a daily planning task, then update the existing recurring planner")
args = parser.parse_args()
os.chdir(ROOT)
REGION = "ap-northeast-2"
ACCOUNT = "590385682315"
FAMILY = "inning-log-crawler"
REGISTRY = f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com"


def aws(*args):
    return json.loads(subprocess.check_output(
        ["aws", *args, "--region", REGION, "--output", "json", "--no-cli-pager"], text=True))


if aws("sts", "get-caller-identity")["Account"] != ACCOUNT:
    sys.exit("Wrong AWS account; expected " + ACCOUNT)
if subprocess.check_output(["git", "status", "--porcelain", "--", "."], text=True).strip():
    sys.exit("Crawler checkout has changes; commit or use a clean worktree before deploying")
tag = subprocess.check_output(["git", "rev-parse", "--short=12", "HEAD"], text=True).strip()
image = f"{REGISTRY}/inning-log/crawler:{tag}"
password = subprocess.check_output(["aws", "ecr", "get-login-password", "--region", REGION])
subprocess.run(["docker", "login", "--username", "AWS", "--password-stdin", REGISTRY], input=password, check=True)
del password
try:
    subprocess.run(["docker", "build", "--platform", "linux/amd64", "-t", image, "."], check=True)
    subprocess.run(["docker", "push", image], check=True)
finally:
    subprocess.run(["docker", "logout", REGISTRY], check=False)

previous = aws("ecs", "describe-task-definition", "--task-definition", FAMILY)["taskDefinition"]
fields = ["family", "taskRoleArn", "executionRoleArn", "networkMode", "containerDefinitions",
          "volumes", "placementConstraints", "requiresCompatibilities", "cpu", "memory",
          "pidMode", "ipcMode", "proxyConfiguration", "inferenceAccelerators", "ephemeralStorage", "runtimePlatform"]
definition = {key: previous[key] for key in fields if key in previous}
container = next(c for c in definition["containerDefinitions"] if c["name"] == "crawler")
container["image"] = image
environment = {e["name"]: e["value"] for e in container.get("environment", [])}
# Scheduler's EcsParameters requires a full ARN, not a family name or family:revision.
# Pin the child game task to this same revision; fail if a concurrent registration races us.
expected_revision = previous["taskDefinitionArn"].rsplit(":", 1)[0] + ":" + str(previous["revision"] + 1)
environment.update(CRAWLER_PROFILE="kbo-live", CRAWLER_KILL_SWITCH="false", CRAWLER_ENABLED="true",
                   CRAWLER_OPERATOR_CONTACT="https://github.com/Inning-Log/Back",
                   CRAWLER_ECS_TASK_DEFINITION=expected_revision, CRAWLER_SCHEDULE_SERIES="auto")
# Replace the old revision's 2-minute override along with the matching quota.
# Keep instance size, task duration and off-day scheduling unchanged.
environment.update(CRAWLER_SCOREBOARD_REFRESH_MINUTES="0.5",
                   CRAWLER_LIVE_SCOREBOARD_REFRESH_SECONDS="15",
                   CRAWLER_DELAYED_REFRESH_MINUTES="1",
                   CRAWLER_MAX_LOGICAL_REQUESTS_PER_HOUR="300",
                   CRAWLER_MAX_ATTEMPTS_PER_HOUR="360")
# Keep approval metadata truthful: this is the operator-requested MVP mode.
for key in list(environment):
    if key.startswith("CRAWLER_AUTH_") or key == "CRAWLER_ROBOTS_EXCEPTION_GRANTED":
        environment.pop(key)
container["environment"] = [{"name": k, "value": v} for k, v in environment.items()]
definition["runtimePlatform"] = {"cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX"}
revision = aws("ecs", "register-task-definition", "--cli-input-json", json.dumps(definition))["taskDefinition"]["taskDefinitionArn"]
if revision != expected_revision:
    sys.exit("Concurrent task registration detected; do not deploy this revision. Re-run after checking the latest definition.")
print(json.dumps({"image": image, "previousRevision": previous["taskDefinitionArn"], "revision": revision}), flush=True)
network = {"awsvpcConfiguration": {
    "subnets": environment["CRAWLER_ECS_SUBNET_IDS"].split(","),
    "securityGroups": environment["CRAWLER_ECS_SECURITY_GROUP_IDS"].split(","),
    "assignPublicIp": "ENABLED" if environment.get("CRAWLER_ECS_ASSIGN_PUBLIC_IP", "true").lower() == "true" else "DISABLED",
}}
cluster = environment["CRAWLER_ECS_CLUSTER_ARN"]
response = aws("ecs", "run-task", "--cluster", cluster, "--task-definition", revision,
               "--launch-type", "FARGATE", "--count", "1", "--network-configuration", json.dumps(network),
               "--overrides", json.dumps({"containerOverrides": [{"name": "crawler", "command": ["--plan-day" if args.activate_planner else "--collect-once", "--profile", "kbo-live"]}]}))
if response.get("failures") or not response.get("tasks"):
    sys.exit(json.dumps(response.get("failures", response)))
task = response["tasks"][0]["taskArn"]
print(json.dumps({"taskArn": task}), flush=True)
deadline = time.monotonic() + 600
while time.monotonic() < deadline:
    item = aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", task)["tasks"][0]
    print(json.dumps({"status": item["lastStatus"]}), flush=True)
    if item["lastStatus"] == "STOPPED":
        break
    time.sleep(15)
else:
    sys.exit("Task still active after 10 minutes; inspect the printed task ARN")
log = container.get("logConfiguration", {}).get("options", {})
completed = None
if log.get("awslogs-group") and log.get("awslogs-stream-prefix"):
    stream = f'{log["awslogs-stream-prefix"]}/crawler/{task.rsplit("/", 1)[1]}'
    events = aws("logs", "get-log-events", "--log-group-name", log["awslogs-group"], "--log-stream-name", stream)
    for event in events.get("events", []):
        print(event["message"], flush=True)
        try:
            record = json.loads(event["message"])
            if record.get("event") == "run_completed":
                completed = record
        except (ValueError, TypeError):
            pass
exit_code = next(c for c in item["containers"] if c["name"] == "crawler").get("exitCode")
print(json.dumps({"exitCode": exit_code, "stoppedReason": item.get("stoppedReason"), "taskArn": task}), flush=True)
if exit_code != 0:
    sys.exit(1)

if args.activate_planner:
    if not completed or completed.get("action") != "plan-day" or completed.get("skipped") or completed.get("anomalies"):
        sys.exit("Planner verification did not complete cleanly; recurring target was not changed")
    name = "inning-log-crawler-plan-daily"
    group = environment["CRAWLER_SCHEDULER_GROUP"]
    old = aws("scheduler", "get-schedule", "--name", name, "--group-name", group)
    old_task = old["Target"]["EcsParameters"]["TaskDefinitionArn"]
    if old_task != previous["taskDefinitionArn"]:
        sys.exit("Planner target changed concurrently; refusing to overwrite it")
    backup = ROOT / "out" / "releases" / tag
    backup.mkdir(parents=True, exist_ok=True)
    allowed = ["Name", "GroupName", "ScheduleExpression", "StartDate", "EndDate", "Description",
               "ScheduleExpressionTimezone", "State", "FlexibleTimeWindow", "Target", "KmsKeyArn", "ActionAfterCompletion"]
    update = {key: old[key] for key in allowed if key in old}
    backup_file = backup / "planner-before.json"
    backup_file.write_text(json.dumps(update, indent=2))
    update["Target"]["EcsParameters"]["TaskDefinitionArn"] = revision
    aws("scheduler", "update-schedule", "--cli-input-json", json.dumps(update))
    verified = aws("scheduler", "get-schedule", "--name", name, "--group-name", group)
    if verified["Target"]["EcsParameters"]["TaskDefinitionArn"] != revision:
        sys.exit("Planner read-back failed; inspect planner and saved rollback input")
    print(json.dumps({"plannerActivated": name, "revision": revision,
                      "expression": verified["ScheduleExpression"], "state": verified["State"],
                      "rollbackInput": str(backup_file)}), flush=True)
