# 실제 KBO 수집 실행

2026-09-14 구현 기준. 기존 인프라는 그대로 사용한다.

실제 실행 식별자와 서비스 DB 연결 상태는 로컬 검증 기록에 보관한다. 내부 인프라 식별자를 포함한 실행 기록은 Git에 올리지 않는다.

일정 화면은 JavaScript가 `/ws/Schedule.asmx/GetScheduleList`로 월간 목록을 요청한다.
HTML만 GET하면 일정 행과 년·월 선택값이 없어 파싱할 수 없다. `kbo-live`는 이 화면의
공개 폼 요청을 월 1회분씩 사용한다. 스코어는 `ScoreBoard.aspx` HTML에서 읽는다.
별도 브라우저 실행, 이미지, 광고, 경기 상세 요청은 없다.

`kbo-live`는 운영자 요청 실행 모드다. 제3자 협약이 체결되었다고 기록하지 않는다.
기본 kill switch는 켜져 있으며 실행 시 `CRAWLER_KILL_SWITCH=false`를 지정한다.
`kbo-locked`의 기존 승인 확인 모드는 유지한다.

## AWS에서 한 번 실행

CloudShell 계정이 **590385682315**, 리전이 **서울**인지 확인한다.
수정 코드가 있는 clean checkout의 `scripts/crawler`에서 실행한다.

```bash
python3 scripts/deploy-live-smoke.py
```

스크립트는 SHA 태그 이미지를 ECR에 올리고 기존 task definition의 IAM·네트워크·로그 설정을
복사한 새 revision으로 Fargate task **1개**를 실행한다. 명령은
`--collect-once --profile kbo-live`다. 반복 Scheduler는 변경하지 않는다.
동일 SHA 이미지를 이미 푸시했다면 Immutable 설정 때문에 재푸시가 실패할 수 있다.

성공 로그에서 다음을 확인한다.

- `source: kbo-pages`: 실제 사이트 응답
- `monthGameCount`: 현재 월 목록에서 읽은 경기 수
- `gameCount`: 오늘 경기 수. 무경기일에는 0이 정상
- `requestMetrics`: 정상 실행은 요청·시도 각각 2회
- `stateReadBackVerified: true`: DynamoDB 저장 후 같은 관측 시각을 재조회해 확인
- `monthPublish`, `publish`: SQS 전송 결과. 내용이 같으면 전송 생략
- 마지막 `exitCode: 0`: Fargate 작업 성공

DynamoDB에는 `SCHEDULE#MONTH#YYYY-MM`, `LATEST#YYYY-MM-DD`가 저장된다.
이는 크롤러 상태 저장이다. 서비스 DB 반영은 별도로 백엔드 SQS consumer 실행과
DB 조회까지 확인해야 한다. SQS 메시지 수만으로 서비스 DB 성공을 판정하지 않는다.

백엔드 설정은 `GAME_SQS_ENABLED=true`, `GAME_SQS_REGION=ap-northeast-2`,
`GAME_SQS_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/590385682315/inning-log-game-snapshots`다.
백엔드 IAM에는 해당 큐의 ReceiveMessage/DeleteMessage 권한이 필요하다.

## 실제 자동 운영 — 2026-09-16 전환

**1회 수집 성공은 자동 운영 완료가 아니다.** 반복 예약의 command와 revision,
실제 예약 실행 로그, 생성된 경기별 날짜 예약까지 함께 확인해야 한다.
9월 15일에는 기존 예약이 fixture dry-run으로 남아 실제 요청이 0회였다.
9월 16일부터 반복 예약을 `--plan-day --profile kbo-live`로 전환했다.

| 단계 | 운영 설정 |
| --- | --- |
| 일정 갱신 | 매일 06:00·12:00·16:00 KST, 짧은 Fargate 작업 1개씩 |
| 범위 | 현재 월 일정 1회 요청, 오늘 경기 시각으로 당일 예약 생성/수정 |
| 경기 작업 시작 | 첫 경기 예정 시각 10분 전 예약. 실제 시작은 Scheduler/컨테이너 기동 지연이 있음 |
| 점수 조회 | 2분 간격. 경기 수만큼 요청하지 않고 스코어보드 1회로 전체 경기 확인 |
| 경기 중 일정 갱신 | 30분 간격, 취소·시간 변경 확인 |
| 지연·중단 상태 | 5분 간격 |
| 정상 종료 | **모든 경기** 종료 감지 후 5·15·30분 추가 확인을 마친 뒤 task 종료 |
| 무경기일 | 계획 작업만 끝내고 경기 작업 예약을 만들지 않음. 이미 예약돼 있다면 제거 |
| 전 경기 취소·연기 | 재확인 후 조기 종료 가능 |
| 최대 실행 | task 시작 기준 12시간. 비용 상한이지 모든 상황에서 종료까지 수집을 보장하는 값은 아님 |

기본 YAML 값과 실제 배포 환경변수는 다를 수 있다. 운영 task definition에는 다음을 적용한다.

```dotenv
CRAWLER_PROFILE=kbo-live
CRAWLER_ENABLED=true
CRAWLER_KILL_SWITCH=false
CRAWLER_PLAN_LEAD_MINUTES=10
CRAWLER_SCOREBOARD_LEAD_MINUTES=10
CRAWLER_SCHEDULE_REFRESH_MINUTES=30
CRAWLER_SCOREBOARD_REFRESH_MINUTES=2
CRAWLER_DELAYED_REFRESH_MINUTES=5
CRAWLER_FINAL_CHECK_MINUTES=5,15,30
CRAWLER_HARD_TIMEOUT_MINUTES=720
```

ECS task definition을 새 revision으로 등록한 후 계획 Scheduler의 task definition과
`CRAWLER_ECS_TASK_DEFINITION`(계획 작업이 생성할 경기 작업의 revision)을 함께 맞춘다.
이 값은 **revision까지 포함한 전체 task definition ARN**이어야 한다.
`inning-log-crawler` 또는 `inning-log-crawler:6` 같은 축약값은 ECS 직접 실행에서는
통할 수 있어도 Scheduler 예약 생성에서는 `Parameters TaskDefinitionArn not valid`로 실패한다.
family 참조 대신 검증된 revision을 고정한다. 이미 생성된 당일 경기 예약도
새 revision인지 조회한다. 경기 시간대를 놓친 날에는 실제 계획 작업을 즉시 한 번 실행한다.

Scheduler의 RetryPolicy는 task 실행 요청 실패에 대한 재시도다. 시작된 컨테이너가
수집 중 실패하는 경우 자동 재시작을 보장하지 않는다. 로그·DLQ·당일 최신 관측을 확인한다.
`hard-timeout`, error, lease 상실을 정상적인 경기 종료 완료로 표시하지 않는다.

파싱 실패의 제한 재관측, 상세 로그 필드, 설정값과 확인 쿼리는 [장애 복구 안내](RECOVERY.md)를 따른다.
해당 변경은 새 이미지 배포가 필요하며 코드 수정만으로 기존 AWS 작업에 적용되지 않는다.

현재 월만 읽으며 시즌 전체를 순회하지 않는다. 시리즈 기본값은 정규시즌이고
`CRAWLER_SCHEDULE_SERIES`로 `0,9,6`(정규), `1`(시범), `3,4,5,7`(포스트)을 선택한다.

403·429·차단 화면 응답에서는 중단하며, 요청 간격·시간당 요청 상한·lease를 유지한다.
즉시 중지는 실행 중 ECS task의 Stop이며, 이후 실행 방지는 Scheduler Disable이다.
task definition의 kill switch 변경만으로 이미 실행 중인 task가 종료되지는 않는다.

## 무경기 시간 비용

크롤러는 상시 ECS Service가 아니라 필요할 때만 실행하는 Fargate task다.
task가 종료되면 크롤러 vCPU/메모리 과금도 종료된다. 단순 sleep으로 task를 켜 둔 시간은
과금되므로, 경기 전 몇 시간 동안 task를 재워 두지 않고 Scheduler에 미래 실행을 맡긴다.
종료 후 최종 확인 30분 동안에는 task가 살아 있으므로 그 시간은 과금된다.

2026-09-16 확인 시 크롤러 cluster에 상시 서비스가 없고, 사용하는 VPC에 NAT Gateway와
VPC endpoint가 없었다. 별도 크롤러 EC2는 만들지 않았다. 다만 ECR 이미지·CloudWatch 로그·
DynamoDB 저장과 요청·SQS·Scheduler·실행 중 public IPv4 등은 별도 비용 항목이다.
기존 백엔드/DB EC2의 상시 비용은 크롤러 task가 멈춰도 그대로다.
백엔드 SQS 소비자도 계속 켜져 있어 빈 큐에 대한 ReceiveMessage 요청 비용은 남는다.
현재 구현은 1초 long-poll 후 기본 2초 지연으로 다시 수신한다. 이는 크롤러 Fargate의
상시 실행과는 다른 비용이며, 더 긴 long-poll 적용은 SDK timeout과 함께 조정해야 한다.

근거: [Fargate 요금](https://aws.amazon.com/fargate/pricing/),
[ECS 요금](https://aws.amazon.com/ecs/pricing/).
