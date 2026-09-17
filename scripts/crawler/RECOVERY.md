# KBO 파싱 장애 진단과 제한 복구

## 확인된 원인과 확인되지 않은 부분

2026-09-16 운영 로그에는 `KBO_PAGE_SCHEMA_MISMATCH`와
`Every KBO scoreboard card failed validation`가 기록됐다.
이는 스코어보드 컨테이너와 날짜 확인은 통과했지만, 모든 경기 카드가 팀·점수·상태·시각·식별자 검증 중
하나 이상에서 탈락했다는 뜻이다. 그 시점의 HTML은 보존되지 않았다.

확정된 코드 결함은 두 가지다.

1. 파서는 `error.details = { anomalies: [...] }`로 카드별 실패 원인을 만들지만,
   기존 오류 로거는 details가 배열일 때만 출력했다. 실제 진단 정보가 사라졌다.
2. 파싱 실패 한 번으로 전역 circuit을 6시간 열고 작업을 종료했다.
   일시적인 불완전 페이지와 지속적인 구조 변경을 구별하지 못했다.

9/17 현재 페이지 단일 조회는 HTTP 200, 카드 2개, anomalies 0이었다.
이는 현재 페이지 파싱 성공이지, 전날 19:07 응답 재현이나 경기 중 파서의 완전성 증명은 아니다.
특정 DOM 변경·표기·점수 불일치가 당시 원인이었다고 단정하지 않는다.

## 변경된 동작

- 파싱 오류: 원인 기록 → 2분 대기 → 새로운 응답으로 재검증. 추가 재시도 최대 2회.
- 검증 실패한 응답은 발행하지 않으며, 해당 응답의 조건부 캐시도 버린다.
  304로 실패한 본문을 다시 사용하는 것을 방지한다.
- 정상 응답이 돌아오면 `schema_recovered`를 기록하고 같은 작업에서 계속 진행한다.
- 총 3회 연속 실패하면 기존 6시간 circuit을 열고 오류 종료한다.
- 모든 재시도는 기존 영속 요청 상한과 요청 간격을 사용한다.
- 계획/경기/단발 수집의 재시도 대기 전 lease를 갱신한다. lease 상실 시 중단한다.
- 경기 작업의 남은 제한 시간 안에 다음 재시도를 시작할 수 없으면 오류 종료한다.
- 403·429·접근 차단 응답은 이 복구 대상이 아니다. 즉시 중단하는 기존 동작을 유지한다.
- 일부 카드 오류가 남은 응답은 정상 최종 확인 횟수에 포함하지 않는다.
- `hard-timeout` 종료는 정상 완료가 아니라 exit code 1로 처리한다.
- 장시간 polling의 대기 함수가 사용한 abort listener는 대기 종료 때 해제한다.

컨테이너가 이미 종료된 뒤 자동으로 새 컨테이너를 만드는 기능은 이 패치에 포함되지 않는다.
Scheduler의 RunTask 재시도만으로 애플리케이션 오류를 복구할 수 없다.
지속적인 구조 오류·권한 오류 등은 원인 확인 후 별도 복구해야 한다. circuit을 무조건 지우고 재실행하지 않는다.

## 조절 가능한 설정

`config/fargate.yml`의 `base.request` 또는 ECS container environment에서 설정한다.

| 환경변수 | 기본값 | 허용 범위 | 의미 |
| --- | --- | --- | --- |
| `CRAWLER_SCHEMA_RETRY_COUNT` | `2` | 정수 0~2 | 최초 실패 후 추가 관측 횟수. 0이면 즉시 circuit |
| `CRAWLER_SCHEMA_RETRY_MINUTES` | `2` | 2~5분 | 파싱 오류 재관측 간격 |

기본값을 유지한다면 환경변수 추가는 필요 없다. 변경한 YAML을 포함한 새 이미지 배포는 필요하다.
ECS 환경변수 변경도 새 task definition revision을 만들고 이후 작업이 그 revision을 사용하도록 해야 한다.
이미 실행 중인 작업에는 적용되지 않는다. 요청 상한은 자동으로 늘어나지 않는다.

## 로그에서 볼 것

모든 운영 이벤트에 `timestamp`(UTC), `runId`, `action`, `profile`, `date`가 붙는다.

| event | 의미 / 핵심 필드 |
| --- | --- |
| `run_started` | 실제 프로세스 진입. task 생성 요청과 구분 |
| `page_observed` | 파싱 완료. page, gameCount, 부분 오류, requestMetrics |
| `schema_observation_failed` | details.anomalies의 card/stage/message와 팀·점수·flag·time |
| `schema_retry_scheduled` | 다음 제한 재시도 시각 retryAt |
| `schema_recovered` | 새 응답으로 복구됨 |
| `snapshot_saved` | 상태 저장 및 발행 단계 완료. published=false도 정상(내용 변화 없음) |
| `circuit_opened` | 반복 파싱 오류/차단 등. code, openUntil |
| `run_failed` | 최종 실패. code, details, 누적 요청 수 |
| `run_completed` | reason 확인 필수. final-checks-complete와 hard-timeout 구별 |

카드 `stage`는 `teams`, `scores`, `status`, `scheduled-time`, `identity` 중 하나다.
본문 바이트 수와 SHA-256을 남겨 같은 불완전 응답이 반복됐는지 비교할 수 있다.
HTML 전체, 응답 헤더 전체, Axios config, 자격증명은 로그에 직렬화하지 않는다.
오류 상세는 허용한 키, 최대 배열 10개, 문자열 길이·중첩 제한으로 축약한다.

스코어보드 파싱 실패 때에는 기존 DynamoDB 상태 테이블에 추가 진단 자료도 기록한다.
키는 `DIAGNOSTIC#날짜#scoreboard#관측시각밀리초#본문해시접두사`이며
CloudWatch 오류 로그의 `details.evidenceKey`로 찾는다.
자료에는 스크립트·폼·입력·이미지·이벤트 속성을 제거한 경기 영역 HTML만 최대 48KB 보존한다.
링크는 인식된 gameId만 남긴다. 쿠키/헤더/전체 페이지는 저장하지 않는다.
TTL은 생성 7일 후로 설정하며 실제 삭제는 DynamoDB의 TTL 정리에 따른다.
자료 저장에 실패해도 `diagnostic_persist_failed`와 원래 파싱 오류를 별도로 남긴다.

AWS 콘솔 → DynamoDB → 상태 테이블 → 항목 탐색에서 파티션 키 `pk`에
로그의 evidenceKey 값을 넣어 조회한다. `payload.evidence.replayable=true`일 때
`payload.evidence.html`을 `parseKboScoreboardPage(html, payload.targetDate)`에 넣어
당시 경기 영역을 재검증할 수 있다. `truncated=true` 또는 영역 자체가 없으면
완전한 재현 자료가 아니므로 카드별 상세 로그와 함께 판단한다.

CloudWatch → Logs Insights → 로그 그룹 `/inning-log/crawler` 선택 → 해당 경기 날짜/시간 범위 설정:

```sql
fields @timestamp, event, runId, page, code, reason, details, requestMetrics
| filter event in ["schema_observation_failed", "schema_retry_scheduled", "schema_recovered", "circuit_opened", "run_failed", "run_completed"]
| sort @timestamp asc
| limit 200
```

같은 `runId`의 `snapshot_saved`를 조회해 마지막 정상 관측을 찾고 PostgreSQL의
`result_observed_at`·수신 영수증과 대조한다. 로그의 `observedAt`만 보지 말고 경기별
`resultObservedAt`도 확인한다. 일정만 다시 읽은 것을 새 점수 수집으로 오해하지 않는다.
최종 확인 대기 간격은 일반 경기 중 polling 간격보다 길다.

## 검증과 배포 구분

```bash
cd scripts/crawler
npm test
node src/fargate/main.js --check-config --profile fixture
```

테스트는 합성 페이지와 메모리 어댑터로 오류 재현·복구·종료·quota·lease·로그 축약을 검증한다.
실제 경기 중 동일 오류가 복구됐다는 뜻은 아니다.
9/17 해당 코드를 새 이미지로 배포하고 실제 사전 수집·DB 반영·오늘 경기 예약 전환을 확인했다.
배포 후 CloudWatch에서 새 event/runId도 확인했다. 이 사전 검증은 경기 종료까지의 관측과 다르다.
추후 변경에도 새 이미지와 task definition을 배포해야 한다. 내부 배포 식별자/증거는 Git 제외
`LIVE_VERIFICATION_2026-09-17.md`에 기록한다.
