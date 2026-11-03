# 경기 영상 저장: AWS 설정 가이드

"영상 저장하기"는 서버, SQS 큐 2개(+DLQ), Lambda 1개, 기존 S3 버킷으로 동작한다.
아래 순서대로 한 번 만들면 된다. 예시는 리전 `ap-northeast-2`, 계정 ID `111122223333`,
버킷 `inninglog-videos`를 쓴다. 실제 값으로 바꿔서 실행한다.

## 0. 전체 흐름

```
앱 ─ POST /api/games/{gameId}/video ─▶ 서버(EC2)
서버 ── RENDER_SEGMENT (이닝마다) ──▶ SQS inninglog-video-jobs ──▶ Lambda inninglog-video-worker
Lambda: S3 inning-records/* 원본 다운로드 → FFmpeg 합성 → S3 segments/<renderKey>.mp4 업로드
Lambda ── SEGMENT_RESULT ──▶ SQS inninglog-video-results ──▶ 서버가 2초마다 폴링
서버: 모든 이닝 READY → MERGE_GAME ──▶ jobs 큐 ──▶ Lambda가 concat(-c copy) → S3 game-videos/...mp4
Lambda ── MERGE_RESULT ──▶ results 큐 ──▶ 서버: READY + 푸시 알림(GENERATED_VIDEO_READY)
```

- Lambda가 3번 실패하면 마지막 시도에서 FAILED 결과를 보내고, 서버는 영상을 FAILED로 바꾸고 알림을 보낸다.
- Lambda가 시간 초과나 크래시로 결과를 못 보내면 메시지는 jobs DLQ로 가고, 서버의 복구 스케줄러가 50분 뒤 FAILED 처리한다.
- 같은 내용의 이닝은 다시 그리지 않는다. 캡션 하나를 고치면 그 이닝만 다시 렌더링한다.

## 1. 계정 동시 실행 한도 확인

```bash
aws lambda get-account-settings --region ap-northeast-2 \
  --query 'AccountLimit.ConcurrentExecutions'
```

기본 한도는 리전당 1,000이지만 새 계정은 더 낮게 시작한다(사용하면서 자동으로 올라간다).
이 설정에서는 SQS 트리거의 **최대 동시성 5**로 제한하므로 10 이상이면 충분하다.
새 계정은 예약 동시성(reserved concurrency)을 설정할 수 없는 경우가 많으니, 아래처럼 트리거 쪽 최대 동시성을 쓴다.

## 2. S3 (기존 버킷 재사용)

새 버킷은 필요 없다. 기존 영상 버킷 안에 접두사만 추가된다.

| 접두사 | 누가 씀 | 내용 |
|---|---|---|
| `inning-records/` | 앱(Presigned PUT) | 원본 이닝 영상 (기존) |
| `segments/` | Lambda | 이닝별 합성 결과(캐시) |
| `game-videos/` | Lambda | 최종 합친 영상 |

`segments/`는 캐시라서 수명 주기 규칙으로 지운다. 지워진 구간은 서버가 확인하고 다시 렌더링한다.

```bash
cat > lifecycle.json <<'JSON'
{
  "Rules": [
    {"ID": "expire-segment-cache", "Status": "Enabled",
     "Filter": {"Prefix": "segments/"}, "Expiration": {"Days": 30}},
    {"ID": "abort-incomplete-uploads", "Status": "Enabled",
     "Filter": {"Prefix": ""}, "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}}
  ]
}
JSON
aws s3api put-bucket-lifecycle-configuration --bucket inninglog-videos --lifecycle-configuration file://lifecycle.json
```

> 이미 수명 주기 규칙이 있으면 위 명령이 **덮어쓴다**. 먼저 `aws s3api get-bucket-lifecycle-configuration --bucket inninglog-videos`로 확인하고 기존 규칙을 합쳐서 넣는다.

## 3. SQS 큐 4개

| 큐 | 용도 | 주요 설정 |
|---|---|---|
| `inninglog-video-jobs-dlq` | jobs 큐의 실패 메시지 | 보존 14일 |
| `inninglog-video-jobs` | 서버 → Lambda | 표시 제한 시간 960초, 보존 1일, 재드라이브 3회 → jobs-dlq |
| `inninglog-video-results-dlq` | results 큐의 실패 메시지 | 보존 14일 |
| `inninglog-video-results` | Lambda → 서버 | 표시 제한 시간 60초, 보존 4일, 재드라이브 5회 → results-dlq |

표시 제한 시간(visibility timeout)은 Lambda 제한 시간(900초)보다 커야 트리거를 만들 수 있다.

```bash
R=ap-northeast-2; A=111122223333
aws sqs create-queue --region $R --queue-name inninglog-video-jobs-dlq \
  --attributes MessageRetentionPeriod=1209600
aws sqs create-queue --region $R --queue-name inninglog-video-results-dlq \
  --attributes MessageRetentionPeriod=1209600

aws sqs create-queue --region $R --queue-name inninglog-video-jobs --attributes '{
  "VisibilityTimeout": "960",
  "MessageRetentionPeriod": "86400",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:'$R':'$A':inninglog-video-jobs-dlq\",\"maxReceiveCount\":\"3\"}"
}'
aws sqs create-queue --region $R --queue-name inninglog-video-results --attributes '{
  "VisibilityTimeout": "60",
  "MessageRetentionPeriod": "345600",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:'$R':'$A':inninglog-video-results-dlq\",\"maxReceiveCount\":\"5\"}"
}'
```

큐 URL은 `https://sqs.ap-northeast-2.amazonaws.com/111122223333/inninglog-video-jobs` 형태다.
`maxReceiveCount`(3)는 Lambda 환경 변수 `MAX_ATTEMPTS`와 같아야 한다.

## 4. Lambda 이미지 빌드 (ECR)

먼저 `video-worker/assets/`에 파일을 넣는다.

- `assets/fonts/regular.ttf`, `assets/fonts/bold.ttf`: 앱 폰트 (영상 삽입이 허용되는 라이선스인지 확인)
- `assets/teams/<team_code>.png`: 투명 배경 팀 로고 10개. 파일 이름은 `kbo_teams.team_code` 값이다.
  `LG.png HH.png SK.png SS.png NC.png KT.png LT.png HT.png OB.png WO.png`

```bash
R=ap-northeast-2; A=111122223333
aws ecr create-repository --region $R --repository-name inninglog-video-worker
aws ecr get-login-password --region $R | docker login --username AWS --password-stdin $A.dkr.ecr.$R.amazonaws.com

cd video-worker
# Apple Silicon Mac에서도 x86_64로 빌드한다. provenance를 끄지 않으면 Lambda가 이미지를 거부할 수 있다.
docker buildx build --platform linux/amd64 --provenance=false \
  -t $A.dkr.ecr.$R.amazonaws.com/inninglog-video-worker:v1 --push .
```

## 5. Lambda 실행 역할 (IAM)

```bash
cat > lambda-trust.json <<'JSON'
{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
  "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
JSON
aws iam create-role --role-name inninglog-video-worker-role --assume-role-policy-document file://lambda-trust.json
aws iam attach-role-policy --role-name inninglog-video-worker-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

cat > lambda-policy.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "ReadSources", "Effect": "Allow", "Action": "s3:GetObject",
     "Resource": ["arn:aws:s3:::inninglog-videos/inning-records/*", "arn:aws:s3:::inninglog-videos/segments/*"]},
    {"Sid": "WriteOutputs", "Effect": "Allow", "Action": "s3:PutObject",
     "Resource": ["arn:aws:s3:::inninglog-videos/segments/*", "arn:aws:s3:::inninglog-videos/game-videos/*"]},
    {"Sid": "ConsumeJobs", "Effect": "Allow",
     "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"],
     "Resource": "arn:aws:sqs:ap-northeast-2:111122223333:inninglog-video-jobs"},
    {"Sid": "ReportResults", "Effect": "Allow", "Action": "sqs:SendMessage",
     "Resource": "arn:aws:sqs:ap-northeast-2:111122223333:inninglog-video-results"}
  ]
}
JSON
aws iam put-role-policy --role-name inninglog-video-worker-role \
  --policy-name inninglog-video-worker --policy-document file://lambda-policy.json
```

버킷이 SSE-KMS(고객 관리 키)로 암호화돼 있으면 그 키에 `kms:Decrypt`, `kms:GenerateDataKey`도 추가한다. 기본 SSE-S3면 필요 없다.

## 6. Lambda 함수 만들기

| 설정 | 값 | 이유 |
|---|---|---|
| 패키지 | 컨테이너 이미지, x86_64 | FFmpeg 정적 빌드 |
| 메모리 | 4096 MB | CPU도 메모리에 비례해서 커진다 |
| 제한 시간 | 900초 | 긴 이닝 대비 (보통 10~30초) |
| 임시 스토리지(/tmp) | 4096 MB | 원본 다운로드와 결과 파일 |
| 환경 변수 | `VIDEO_BUCKET`, `RESULT_QUEUE_URL`, `MAX_ATTEMPTS=3` | |

```bash
R=ap-northeast-2; A=111122223333
aws lambda create-function --region $R --function-name inninglog-video-worker \
  --package-type Image --architectures x86_64 \
  --code ImageUri=$A.dkr.ecr.$R.amazonaws.com/inninglog-video-worker:v1 \
  --role arn:aws:iam::$A:role/inninglog-video-worker-role \
  --memory-size 4096 --timeout 900 --ephemeral-storage Size=4096 \
  --environment "Variables={VIDEO_BUCKET=inninglog-videos,RESULT_QUEUE_URL=https://sqs.$R.amazonaws.com/$A/inninglog-video-results,MAX_ATTEMPTS=3}"
```

이미지를 다시 올린 뒤에는 이렇게 바꾼다.

```bash
aws lambda update-function-code --region $R --function-name inninglog-video-worker \
  --image-uri $A.dkr.ecr.$R.amazonaws.com/inninglog-video-worker:v2
```

## 7. SQS 트리거 연결

배치 크기 1(메시지 하나 = 이닝 하나), 부분 실패 보고 켜기, 최대 동시성 5.

```bash
aws lambda create-event-source-mapping --region $R --function-name inninglog-video-worker \
  --event-source-arn arn:aws:sqs:$R:$A:inninglog-video-jobs \
  --batch-size 1 --function-response-types ReportBatchItemFailures \
  --scaling-config MaximumConcurrency=5
```

동시성은 예약 동시성 대신 트리거의 `MaximumConcurrency`로 제한한다. 예약 동시성으로 막으면 스로틀된 메시지도
수신 횟수에 포함되어, 실패하지 않은 작업이 DLQ로 갈 수 있다.

## 8. 서버(EC2) 역할 권한 추가

기존 EC2 인스턴스 역할에 아래 정책을 추가한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Sid": "SendJobs", "Effect": "Allow", "Action": "sqs:SendMessage",
     "Resource": "arn:aws:sqs:ap-northeast-2:111122223333:inninglog-video-jobs"},
    {"Sid": "ReadResults", "Effect": "Allow",
     "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
     "Resource": "arn:aws:sqs:ap-northeast-2:111122223333:inninglog-video-results"},
    {"Sid": "GameVideoObjects", "Effect": "Allow", "Action": ["s3:GetObject", "s3:DeleteObject"],
     "Resource": ["arn:aws:s3:::inninglog-videos/game-videos/*", "arn:aws:s3:::inninglog-videos/segments/*"]},
    {"Sid": "SegmentExistsCheck", "Effect": "Allow", "Action": "s3:ListBucket",
     "Resource": "arn:aws:s3:::inninglog-videos"}
  ]
}
```

`s3:ListBucket`이 없으면 S3가 없는 파일에 404 대신 403을 돌려줘서, 캐시가 만료된 구간 확인이 실패한다.
이 확인에는 접두사 정보가 전달되지 않으므로 `s3:prefix` 조건을 붙이면 동작하지 않는다(키 목록 조회 권한이며 파일 내용은 읽을 수 없다).

## 9. 서버 환경 변수

기존 `VIDEO_S3_*` 설정(버킷)은 그대로 두고 아래를 추가한다.

```env
GAME_VIDEO_ENABLED=true
GAME_VIDEO_SQS_REGION=ap-northeast-2
GAME_VIDEO_JOB_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/111122223333/inninglog-video-jobs
GAME_VIDEO_RESULT_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/111122223333/inninglog-video-results
# 선택 (기본값)
# GAME_VIDEO_MAX_FRIENDS=5
# GAME_VIDEO_SEGMENT_TIMEOUT=50m
# GAME_VIDEO_MERGE_TIMEOUT=50m
# GAME_VIDEO_TEMPLATE_VERSION=v1   # 워커의 그림을 바꾸면 v2로 올려서 캐시를 무효화
```

`GAME_VIDEO_ENABLED=false`(기본)면 저장 API는 503 `GAME_VIDEO_DISABLED`를 돌려준다.
`VIDEO_S3_ENABLED=true`도 함께 필요하다.

## 10. 동작 확인

1. 서버를 재시작하고 로그에 `Game video result polling failed`가 반복되지 않는지 본다(권한·URL 오류 확인용).
2. 영상이 있는 경기에서 `POST /api/games/{gameId}/video` → 202, `status: RENDERING`.
3. CloudWatch Logs `/aws/lambda/inninglog-video-worker`에서 실행 로그 확인.
4. `GET /api/games/{gameId}/video`가 `MERGING` → `READY`가 되고 `downloadUrl`이 생기는지 확인.
5. S3에 `segments/…mp4`, `game-videos/users/…mp4`가 생겼는지 확인.

Lambda만 따로 확인하려면 콘솔의 jobs 큐 "메시지 전송"에 아래 형식으로 넣고 results 큐에서 결과를 본다
(`sourceKey`는 실제 원본 키로 바꾼다).

```json
{"type":"RENDER_SEGMENT","renderKey":"manual-test","outputKey":"segments/manual-test.mp4",
 "segment":{"header":{"awayTeamCode":"HH","homeTeamCode":"OB","awayScore":"1","homeScore":"0",
   "datetime":"2026.06.03 (수) 18:30","stadium":"잠실야구장"},
  "inning":1,"half":"TOP","solo":true,"footer":{"handle":"dayeon","team":"한화"},
  "members":[{"name":"다연","team":"한화","clips":[
    {"sourceKey":"inning-records/users/1/records/1/xxx.mp4","time":"18:43","caption":"야호","score":"0 : 0"}]}]}}
```

## 11. 모니터링

- jobs DLQ `ApproximateNumberOfMessagesVisible` > 0 이면 CloudWatch 알람(→ 이메일/SNS). 크래시·시간 초과 작업이다.
- Lambda `Errors`, `Duration` 지표.
- DLQ 메시지를 다시 처리하려면 SQS 콘솔의 "DLQ 재드라이브 시작"으로 jobs 큐에 돌려보낸다.

## 12. 비용 감각

x86 Lambda는 GB-초당 $0.0000166667, 프리 티어는 매달 요청 100만 건과 400,000 GB-초다.
4GB로 이닝 하나에 15초 걸리면 60 GB-초이므로, 프리 티어 안에서 이닝 약 6,600개(9이닝 경기 약 700개)를 매달 처리할 수 있다.
그다음부터는 이닝당 약 $0.001이다. SQS는 월 100만 요청까지 무료이고, 서버 폴링(약 3초마다, 빈 응답 포함)이
월 약 90만 요청이라 다른 SQS 사용량과 합쳐 100만을 넘으면 소액(100만 요청당 $0.4)이 나온다. 줄이려면 `GAME_VIDEO_POLL_DELAY`를 5s로 올린다.
