"""AWS Lambda entry point for game video jobs (SQS trigger, batch size 1).

RENDER_SEGMENT: download the clips, render one inning segment, upload it to outputKey.
MERGE_GAME:     download the segments, join them without re-encoding, upload the game video to outputKey.
Each job reports SEGMENT_RESULT / MERGE_RESULT to RESULT_QUEUE_URL. A job that keeps failing reports FAILED on its
last attempt so the user hears about it quickly; crashes and timeouts end up in the job queue's DLQ and the
server's recovery scheduler fails them after its timeout.
"""
import json
import os
import shutil
import subprocess
import tempfile
import traceback

import boto3

import render_segment

BUCKET = os.environ["VIDEO_BUCKET"]
RESULT_QUEUE_URL = os.environ["RESULT_QUEUE_URL"]
# Must match the job queue's redrive policy maxReceiveCount
MAX_ATTEMPTS = int(os.environ.get("MAX_ATTEMPTS", "3"))

s3 = boto3.client("s3")
sqs = boto3.client("sqs")


def handler(event, context):
    failures = []
    for record in event.get("Records", []):
        try:
            process(record)
        except Exception:  # noqa: BLE001 - reported below, then retried by SQS
            traceback.print_exc()
            failures.append({"itemIdentifier": record["messageId"]})
    # Requires ReportBatchItemFailures on the event source mapping
    return {"batchItemFailures": failures}


def process(record):
    job = json.loads(record["body"])
    attempt = int(record.get("attributes", {}).get("ApproximateReceiveCount", "1"))
    work = tempfile.mkdtemp(dir="/tmp")
    try:
        if job["type"] == "RENDER_SEGMENT":
            duration = render(job, work)
            report({"type": "SEGMENT_RESULT", "renderKey": job["renderKey"], "status": "READY", "durationSeconds": duration})
        elif job["type"] == "MERGE_GAME":
            duration = merge(job, work)
            report({"type": "MERGE_RESULT", "gameVideoId": job["gameVideoId"], "status": "READY", "durationSeconds": duration})
        else:
            print(f"unknown job type {job.get('type')!r}, dropped")
    except Exception as error:
        if attempt < MAX_ATTEMPTS:
            raise
        reason = classify(error)
        if job.get("type") == "RENDER_SEGMENT":
            report({"type": "SEGMENT_RESULT", "renderKey": job["renderKey"], "status": "FAILED", "error": reason})
        elif job.get("type") == "MERGE_GAME":
            report({"type": "MERGE_RESULT", "gameVideoId": job["gameVideoId"], "status": "FAILED", "error": reason})
        traceback.print_exc()
    finally:
        shutil.rmtree(work, ignore_errors=True)


def render(job, work):
    segment = job["segment"]
    members = []
    n = 0
    for member in segment["members"]:
        clips = []
        for clip in member["clips"]:
            ext = os.path.splitext(clip["sourceKey"])[1] or ".mp4"
            path = os.path.join(work, f"src_{n}{ext}")
            n += 1
            s3.download_file(BUCKET, clip["sourceKey"], path)
            clips.append({"path": path, "time": clip["time"], "caption": clip["caption"], "score": clip["score"]})
        members.append({"name": member["name"], "team": member["team"], "clips": clips})
    local = {"header": segment["header"], "inning": segment["inning"], "half": segment.get("half"),
             "solo": segment["solo"], "footer": segment["footer"], "members": members}
    out = os.path.join(work, "segment.mp4")
    duration = render_segment.render(local, out, work)
    upload(out, job["outputKey"])
    return round(duration, 3)


def merge(job, work):
    listing = os.path.join(work, "list.txt")
    with open(listing, "w", encoding="utf-8") as f:
        for i, key in enumerate(job["segmentKeys"]):
            path = os.path.join(work, f"seg_{i}.mp4")
            s3.download_file(BUCKET, key, path)
            f.write(f"file '{path}'\n")
    out = os.path.join(work, "game.mp4")
    # every segment has the same encoding settings, so the streams are copied as they are
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", listing,
                    "-c", "copy", "-movflags", "+faststart", out], check=True)
    upload(out, job["outputKey"])
    return round(render_segment.probe_duration(out), 3)


def upload(path, key):
    s3.upload_file(path, BUCKET, key, ExtraArgs={"ContentType": "video/mp4"})


def report(result):
    sqs.send_message(QueueUrl=RESULT_QUEUE_URL, MessageBody=json.dumps(result))


def classify(error):
    name = type(error).__name__
    if "NoSuchKey" in str(error) or "404" in str(error):
        return "SOURCE_MISSING"
    if isinstance(error, FileNotFoundError):
        return "ASSET_MISSING"
    if isinstance(error, subprocess.CalledProcessError):
        return "FFMPEG_FAILED"
    return name[:100]
