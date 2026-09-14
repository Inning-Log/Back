# 홈·경기·관람 API

구현일: 2026-09-14. 이 문서가 현재 구현의 프론트 연동 계약이다. 초기 판단 과정은 [설계안](home-calendar-win-rate-plan.md)을 참고한다.

## 동작 범위

- 경기 일정/결과는 수집 snapshot을 PostgreSQL에 저장한 뒤 조회한다.
- 사용자는 경기당 직관/집관 1개를 등록하고 관람 방식 정정·삭제를 할 수 있다.
- 달력은 현재 최애팀 일정과 내 관람 경기의 합집합이며, 날짜별 경기 배열을 제공한다.
- 상단 승률은 **KST 현재 연도 정규시즌, 직관, 해당 경기에서 응원한 팀** 기준이다. 달력 월 이동과 독립적이다.
- 승률은 `승 / (승 + 패) × 100`, 소수 첫째 자리 반올림이다. 무승부·진행중·취소·결과 미확인은 분모에서 제외한다. 분모가 없으면 null이다.
- 영상 업로드·지난 영상 열람은 후속 기능이다. 관람 기록이 있으면 현재 `recordingState=UNAVAILABLE`이며, 없는 경우에는 `NONE`이다. 이 값을 영상 있음/없음으로 임의 변환하지 않는다.

모든 아래 API는 `Authorization: Bearer <accessToken>`을 요구하며 온보딩을 완료한 활성 사용자만 사용할 수 있다. `userId`는 JWT에서 결정한다. 구장 표시는 현재 수집 원문 `stadiumName`으로 제공한다. 구장 FK/별칭 정규화는 후속 확장이다.

## API 목록

| 메서드·경로 | 의미 | 성공 응답 |
| --- | --- | --- |
| `GET /api/home/calendar?year=2026&month=9` | 월간 달력 | 200 |
| `GET /api/home/win-rate` | 현재 정규시즌 직관 승률 | 200 |
| `GET /api/games?date=2026-09-14` | 날짜별 전체 팀 경기와 내 관람 여부 | 200 |
| `POST /api/user-game-logs` | 직관/집관 등록 | 최초 201, 동일 재시도/복원 200 |
| `GET /api/user-game-logs/{id}` | 내 관람 기록 상세 | 200 |
| `PATCH /api/user-game-logs/{id}` | 내 관람 방식 정정 | 200 |
| `DELETE /api/user-game-logs/{id}` | 내 관람 기록 소프트 삭제 | 204 |

Swagger `/swagger-ui.html`, OpenAPI `/v3/api-docs`에서도 확인할 수 있다. 다음 예시는 실제 경기 결과가 아닌 응답 구조 설명용 데이터다.

## 달력

`year`: 1900~9998, `month`: 1~12. 지정한 달의 모든 날짜를 순서대로 반환한다. 빈 날짜도 `games: []`가 있으며 앞뒤 달의 회색 날짜는 프론트에서 채운다.

```json
{
  "year": 2026,
  "month": 9,
  "timezone": "Asia/Seoul",
  "today": "2026-09-14",
  "favoriteTeamId": 5,
  "scheduleData": { "state": "IMPORTED", "lastAppliedAt": "2026-09-14T02:00:00Z" },
  "days": [
    { "date": "2026-09-01", "games": [] }
  ]
}
```

예시에서는 days를 축약했지만 실제 9월 응답에는 30개 항목이 들어간다. 날짜마다 `games` 배열을 사용하므로 더블헤더를 한 경기로 덮어쓰지 않는다.

각 `games` 항목:

| 필드 | 타입·의미 |
| --- | --- |
| `gameId` | 내부 경기 ID. 관람 등록에 사용 |
| `seasonYear`, `gameType`, `date` | 소속 시즌, 경기 종류, KST 경기일 |
| `gameSequence` | 확인된 0=일반, 1/2=더블헤더, 미확정 null |
| `scheduledAt` | UTC ISO 시각 또는 null |
| `status` | 아래 경기 상태 |
| `homeTeam`, `awayTeam` | `{id, teamCode, name, shortName, logoUrl}` |
| `stadiumName` | 구장 표시명 또는 null |
| `score` | `{home: number|null, away: number|null}` |
| `displayTeam`, `opponentTeam` | 달력의 기준팀/상대팀. 전체 경기 조회에서 내 팀과 무관하고 관람 미등록이면 null |
| `displayResult` | 표시 기준팀 관점의 경기 결과 |
| `myViewing` | `{id, viewingType, cheeringTeamId}` 또는 null |
| `recordingState` | 현재 `NONE` 또는 `UNAVAILABLE`. 영상 기능 연동 후 READY 추가 예정 |
| `resultObservedAt` | 현재 결과 필드의 실제 원본 관측 시각 |

현재 최애팀이 참가하는 경기는 그 팀을 표시 기준으로 사용한다. 현재 최애팀 경기가 아닌 내 기록은 당시 응원팀을 기준으로 표시한다. 개인 승률은 항상 관람 기록의 `cheeringTeamId`를 사용한다.

경기 상태: `UNKNOWN`, `SCHEDULED`, `LIVE`, `FINISHED`, `CANCELED`, `POSTPONED`, `DELAYED`, `SUSPENDED`.

결과: `WIN`, `LOSS`, `DRAW`, `PENDING`, `VOID`, `UNKNOWN`. FINISHED이고 양쪽 점수가 유효한 경우에만 승/패/무를 판정한다. 미확인 점수를 0으로 표시하지 않는다.

`scheduleData.state`:

- `NOT_IMPORTED`: 해당 범위의 일정이 아직 수입되지 않았다. “경기 없음”으로 단정하지 않는다.
- `IMPORTED`: snapshot 수입 완료. 경기 배열이 비어 있으면 그 범위에서 확인된 일정은 없다.
- `PARTIAL`: 수집기가 파싱 이상 등의 anomalies를 보고했다. 이전에 저장된 경기 행은 유지한다.

`lastAppliedAt`은 서버 DB 반영 시각이다. 수집기가 변경 없는 결과를 재발행하지 않으므로 마지막 수집 성공 시각과 같지 않다.

## 직관 승률

```json
{
  "seasonYear": 2026,
  "gameTypes": ["REGULAR"],
  "viewingType": "STADIUM",
  "registeredCount": 13,
  "wins": 7,
  "losses": 4,
  "draws": 1,
  "pendingCount": 1,
  "voidCount": 0,
  "unknownCount": 0,
  "unclassifiedCount": 0,
  "decidedCount": 11,
  "winRatePercent": 63.6,
  "calculatedAt": "2026-09-14T02:00:00Z"
}
```

- `registeredCount = wins + losses + draws + pendingCount + voidCount + unknownCount`. 모두 해당 정규시즌의 활성 직관 기록이다.
- `unclassifiedCount`는 경기 종류가 UNKNOWN인 같은 시즌 직관 기록 수다. 위 registeredCount와 분모에 포함하지 않으며 정규시즌 분류가 확인되면 재조회에 반영된다.
- 비시즌에도 KST 현재 연도가 기준이며, 전년 기록으로 자동 전환하지 않는다.
- 집관 영상 유무나 영상 개수는 승률을 바꾸지 않는다. 명시적으로 등록한 직관이 기준이다.
- 최애팀 변경은 기존 관람 기록의 응원팀을 바꾸지 않는다.

## 날짜별 경기

`GET /api/games?date=YYYY-MM-DD`는 `{date, today, scheduleData, games}`를 반환한다. `games` 구조는 달력 항목과 같다. 최애팀 필터 없이 전체 경기 중에서 등록 대상을 선택할 수 있다.

당일 snapshot 상태가 있으면 사용하고 없으면 해당 월 상태를 사용한다. 관람 미등록도 정상 상태이므로 `myViewing: null`이다.

## 관람 등록·정정·삭제

```json
{
  "gameId": 1001,
  "cheeringTeamId": 5,
  "viewingType": "STADIUM"
}
```

`STADIUM`=직관, `HOME`=집관. 응원팀은 그 경기의 홈팀 또는 원정팀이어야 한다. 최초 등록/삭제 기록 복원은 KST 당일 경기만 가능하고 취소·연기 경기는 거절한다. 기록하기 진입 시 달력 선택 날짜와 무관하게 응답의 `today`를 사용한다.

응답: `{id, gameId, cheeringTeamId, viewingType, result, createdAt, updatedAt}`. 신규 생성은 201과 Location 헤더를 반환한다. 동일 사용자·경기·내용의 재시도는 기존 ID와 200이며, 자정이 지났거나 경기 취소 후에도 이미 저장된 요청의 재시도는 성공한다. 다른 내용으로 POST하면 409이다.

정정 요청은 아래처럼 보낸다. 경기/응원팀 변경은 이 API에서 지원하지 않는다.

```json
{ "viewingType": "HOME" }
```

삭제는 soft delete이며 반복 DELETE도 204다. 같은 날 재등록하면 기존 ID를 복원한다. 본인 소유가 아닌 기록은 조회/수정/삭제 모두 404다. 탈퇴 시 활성 관람 기록도 soft delete한다.

홈 진입 시 달력·승률을 병렬 조회하고, 월 이동에는 달력만 다시 조회한다. 관람 등록/정정/삭제 이후 관련 달력과 승률을 다시 조회한다. 서버는 승률을 저장하거나 별도 캐싱하지 않는다.

## 오류

요청 검증과 도메인 오류 응답은 `{code, message, timestamp}`다. 401은 기존 Spring Security 응답을 따른다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 날짜/연월/본문/응원팀 검증 실패 |
| 404 | `USER_NOT_FOUND`, `GAME_NOT_FOUND`, `VIEWING_NOT_FOUND` | 사용자·경기·본인 기록 없음 |
| 409 | `PROFILE_SETUP_REQUIRED` | 온보딩 미완료 |
| 409 | `ACCOUNT_DELETED` | 쓰기 시 탈퇴 계정 확인 |
| 409 | `RECORDING_DATE_NOT_TODAY` | 당일 이외 신규 등록/복원 |
| 409 | `GAME_NOT_RECORDABLE` | 취소·연기 경기 |
| 409 | `VIEWING_ALREADY_EXISTS` | 기존 관람 내용과 다른 POST |

## 수집 데이터 연결

Flyway V15에서 경기·외부 ID·관람 기록·수집 처리 이력/범위 상태를 생성한다. 기존 PostgreSQL 사용자 데이터는 유지한다. 저장/조회에는 Spring JDBC를 사용하고 사용자 인증·행 잠금에는 기존 JPA 사용자 저장소를 재사용한다. 홈 집계는 DB 집계 쿼리로 수행한다.

운영 SQS 소비자는 기본 비활성이다. 연결할 때 다음 설정을 지정한다.

```text
GAME_SQS_ENABLED=true
GAME_SQS_REGION=ap-northeast-2
GAME_SQS_QUEUE_URL=<기존 경기 snapshot SQS URL>
GAME_IMPORT_ALLOW_FIXTURES=false
```

AWS 자격증명은 SDK 기본 자격증명 체인/실행 역할을 사용한다. 실행 역할에는 대상 queue의 ReceiveMessage/DeleteMessage 권한이 필요하다. 실패 메시지를 보관할 DLQ/redrive 설정을 기존 queue에서 확인한다. 구현은 1건씩 수신하고 DB commit 이후 ACK한다. 소비 실패는 ACK하지 않아 SQS 재시도/DLQ로 남는다. 별도 스케줄러를 사용해 알림 발송 작업과 분리한다.

현재 `KBO_SCHEDULE_MONTH_SNAPSHOT`, `KBO_GAME_SNAPSHOT`의 schemaVersion 1을 지원한다. 서버 어댑터는 KIA→HT, DOO→OB, SSG→SK, WOE→WO와 CANCELLED→CANCELED를 변환한다.

이번 수집기 변경과 서버를 함께 반영해야 한다.

- `game-window`의 각 경기에는 `meta.scheduleObservedAt`, `meta.resultObservedAt`, `meta.resultSource`(SCHEDULE/SCOREBOARD)가 필요하다. 오래된 메시지에 없으면 `MISSING_FIELD_OBSERVATION`으로 실패시켜 DLQ에 보존한다. 임의로 현재 시각을 채워 재전송하지 않는다.
- 일정 페이지에서 선택된 경기 종류가 명확할 때만 `gameType`을 채운다. 선택 정보가 없으면 UNKNOWN이다. 실제 페이지가 지원하지 않는 라벨/구조를 보내면 정규시즌으로 추정하지 않고 관측된 HTML/허용 범위에 맞춰 파서를 보완한다.
- `gameNumber`를 일반/DH 회차로 복사하지 않는다. 현 파서는 `gameSequence:null`을 전달한다. 명시적 0/1/2 입력은 서버가 지원하지만 UI가 null을 일반 경기로 단정해서는 안 된다.
- 외부 ID를 우선하고, 없으면 같은 팀/날짜에서 확인된 회차나 정확히 일치하는 시작 시각으로 매칭한다. 식별 불가능한 일정 변경은 `AMBIGUOUS_GAME_IDENTITY`로 보류한다. 실패한 snapshot 전체를 rollback하며 운영자는 원본 메시지/명시적 ID를 확인한 뒤 재처리한다.
- 같은 내용 A→B→A 정정도 반영한다. 이벤트 멱등키는 정규화 내용과 관측 시각으로 계산하며, 내용 hash만으로 과거 정정을 중복 처리하지 않는다.
- 과거 관측/캐시 점수로 새 결과를 덮어쓰지 않는다. 종료 이후 예정 상태로 되돌리지 않는다. snapshot에서 빠진 경기를 취소/삭제하지 않는다.

## 로컬 확인

기존 local 프로필과 DB 설정으로 서버를 시작한다. 테스트 fixture 수입이 필요할 때에만 `GAME_IMPORT_ALLOW_FIXTURES=true`를 설정한다.

```powershell
$env:GAME_IMPORT_ALLOW_FIXTURES = 'true'
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

local 프로필 + `app.auth.dev-token-enabled=true`에서만 인증된 `POST /api/dev/game-snapshots`가 존재한다. 공용 API/운영 환경에는 점수 쓰기 엔드포인트를 제공하지 않는다.

생산자 코드에서 생성한 합성 메시지는 `src/test/resources/game-snapshots/crawler-month.json`, `crawler-game-window.json`이다. 월간→당일 순서로 개발 엔드포인트에 보내거나 통합 테스트로 검증할 수 있다. fixture의 경기일은 2026년 8월이므로 당일 관람 등록 예시로 사용할 때에는 별도의 당일 합성 경기를 사용한다.

```powershell
# 수집기 디렉터리에서 실행. KBO/AWS 호출 없음.
npm.cmd run fixture:backend

# 백엔드 루트에서 전체 테스트
.\gradlew.bat test

# 반드시 격리된 테스트 DB를 지정. 운영 DB를 지정하지 않음.
$env:GAME_TEST_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55439/inning_game_test'
$env:GAME_TEST_DB_USER = '<테스트 DB 사용자>'
$env:GAME_TEST_DB_PASSWORD = '<테스트 DB 비밀번호>'
.\gradlew.bat test --tests 'com.inninglog.domain.game.*' --rerun-tasks
```

검증은 인증/요청 오류, 관람 재시도·동시성·복원·소유권, 시즌/시간대/무승부 집계, 더블헤더/최애팀 변경, 수집 중복·역순·결과 정정·전체 rollback, SQS ACK 순서, 실제 수집 메시지 계약을 포함한다. 운영 AWS 연결과 영상 연동은 이 로컬 검증에 포함하지 않는다.

### 구현 검증 결과 (2026-09-14)

- 전체 백엔드 테스트: 128개 통과, 실패/건너뜀 0개.
- 신규 경기·홈 테스트를 격리 PostgreSQL 17.11에서 재실행: 23개 통과.
- 수집기 Node 테스트: 93개 통과.
- 실행 JAR + 별도 PostgreSQL DB + 실제 JWT/HTTP 요청: 17개 확인 통과. 합성 일정 수입, 달력 조회, 직관 등록/재시도, 승률 100%, 집관 정정 후 집계 제외, 삭제 후 미노출, 인증/입력 오류, OpenAPI 노출을 확인했다.
- 검증에 사용한 경기와 계정은 격리 DB의 합성 데이터다. 실제 KBO 경기 정확성이나 운영 SQS 연결을 검증한 결과가 아니다.
