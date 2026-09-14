# 실제 KBO 수집 실행

2026-09-14 구현 기준. 기존 인프라는 그대로 사용한다.

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

## 반복 실행

검증한 revision을 일간 Scheduler의 task definition으로 지정하고 인수를
`--plan-day --profile kbo-live`로 변경한다. 기본 계획은 첫 경기 60분 전 작업 시작,
스코어는 첫 경기 10분 전부터 2분 간격, 일정 갱신은 15분 간격이다.
지연 경기는 5분, 종료 후 최종 확인은 5·30·90분이고 최대 실행은 12시간이다.
현재 월만 읽으며 시즌 전체를 순회하지 않는다. 시리즈 기본값은 정규시즌이고
`CRAWLER_SCHEDULE_SERIES`로 `0,9,6`(정규), `1`(시범), `3,4,5,7`(포스트)을 선택한다.

403·429·차단 화면 응답에서는 중단하며, 요청 간격·시간당 요청 상한·lease를 유지한다.
즉시 중지는 실행 중 ECS task의 Stop이며, 이후 실행 방지는 Scheduler Disable이다.
task definition의 kill switch 변경만으로 이미 실행 중인 task가 종료되지는 않는다.
