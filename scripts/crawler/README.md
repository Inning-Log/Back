# Inning Log 경기 수집기

정책 검사를 통과한 범위에서만 경기 데이터를 정규화하는 저부하 수집기다. 기본값은 외부 네트워크를 전혀 사용하지 않는 **합성 fixture 프로필**이며, 실 서버 수집은 서면 허가와 범위 증빙 없이는 차단한다. 제품의 확정 대상은 KBO의 경기일정·결과와 스코어보드 두 페이지뿐이고 NAVER는 운영 범위에서 제외한다.

수집 URL·필드·주기는 [TARGET_SCOPE.md](./TARGET_SCOPE.md), 정책 판단은 [POLICY.md](./POLICY.md), 실행·장애·삭제 절차는 [OPERATIONS.md](./OPERATIONS.md)를 먼저 읽는다. AWS 배포는 [AWS_FARGATE_SETUP.md](./AWS_FARGATE_SETUP.md)를 따른다.

## 빠른 시작

요구 환경은 Node.js 22 이상이다.

```bash
cd Inning-Log/scripts/crawler
npm ci
npm run config:check -- --profile fixture
npm run crawl -- --profile fixture --dry-run
npm run crawl -- --profile fixture
```

도움말과 최종 설정 확인:

```bash
npm run crawl -- --help
npm run config:print -- --profile fixture
```

`fixture`는 합성 입력만 읽으며 네트워크 요청을 만들지 않는다. 개발·테스트·CI·UI 연동에서는 이 프로필을 사용한다.

AWS Fargate용 KBO 두 페이지 전용 경로는 별도 CLI로 격리되어 있다.

```bash
npm run fargate:config:check -- --profile fixture
npm run fargate:policy:check -- --profile kbo-locked
npm run fargate:plan -- --profile fixture --dry-run
```

`kbo-locked`는 코드가 완성되어 있어도 기본 `enabled: false`, kill switch 활성, 승인 `unverified`라 정책 검사가 의도적으로 실패한다. `--check-config`, `--check-policy`, `--dry-run`은 KBO나 AWS를 호출하지 않는다. 실제 Fargate 실행은 `src/fargate/main.js`만 사용하며 URL은 다음 두 문자열로 코드에 고정되어 환경변수로 바꿀 수 없다.

- `https://www.koreabaseball.com/Schedule/Schedule.aspx`
- `https://www.koreabaseball.com/Schedule/ScoreBoard.aspx`

## 안전 정책 요약

- KBO robots.txt는 2026-08-25 확인 기준 전체 자동 탐색을 거부한다.
- KBO 이용약관 제14조는 사전 서면 동의 또는 공식 API 없이 대량·반복 자동 수집을 금지하고 재판매·재배포·타 플랫폼 연동·2차 가공을 제한한다.
- NAVER는 제품 범위에서 제외하며 운영 profile, 네트워크 allowlist와 AWS 배포에 포함하지 않는다.
- 학생·비영리 프로젝트도 위 조건의 면제가 아니다.

따라서 실제 네트워크 프로필은 **서면 허가 + 정확한 범위 증빙 + 최신 정책·robots 검증 + 정책 검사 통과** 전에는 실행하지 않는다.

실 제공처 정책 게이트는 최소한 다음을 확인한다.

- 전역 실행이 활성화되고 kill switch가 꺼져 있으며 HTTPS·fail-closed가 유지되는가
- KBO 하나만 선택되어 있고 허용 경로가 `/Schedule/Schedule.aspx`, `/Schedule/ScoreBoard.aspx` 두 개로 고정되어 있는가
- 승인이 `approved`이고, 서면 증빙 식별자·최근 검토 시각·미래의 명시적 만료 시각이 있는가
- 일정 페이지에는 `schedule-page`, 스코어보드에는 `scoreboard-page` scope가 승인되어 있는가
- KBO의 전체 robots 차단에서 두 경로를 예외로 할 수 있는 서면 범위 및 `robots-disallow-override` scope가 있는가
- 게임센터·문자중계·내부 ASMX/JSON endpoint가 요청 목록에 섞이지 않았는가
- 식별 가능한 User-Agent 연락처, 고정 host/path와 7일 이내 robots 재검토·서면 예외 기록이 유효한가

설정값만 임의로 `true`로 바꾸는 것은 증빙이 아니며, 검사 통과를 위해 승인 상태를 꾸며서는 안 된다.

## 구성

```text
scripts/crawler/
├─ config/
│  ├─ crawler.yml       과거 범용 CLI와 회귀 테스트 설정
│  └─ fargate.yml       KBO page-only Fargate 설정
├─ fixtures/            네트워크 없는 합성 입력과 KBO DOM fixture
├─ src/
│  ├─ crawler.js        CLI와 실행 오케스트레이션
│  ├─ config.js         YAML·환경변수·CLI 설정 로더와 검증
│  ├─ policy.js         실행 허가, robots, 범위와 kill-switch 검사
│  ├─ http.js           호스트 제한, 직렬화, 재시도와 회로 차단
│  ├─ providers/        허가된 입력 또는 fixture 어댑터
│  ├─ domain.js         제공처 독립 경기 정규형
│  ├─ merge.js          과거 다중 제공처 검토용 병합 계층
│  ├─ scheduler.js      상태 기반 적응형 폴링 판단
│  ├─ storage.js        변경 감지, 원자적 저장과 실행 잠금
│  └─ fargate/
│     ├─ main.js        plan-day/run-game-window 전용 CLI
│     ├─ config.js      고정 URL, 환경변수와 승인 정책 검증
│     ├─ kbo-pages.js   두 KBO HTML 페이지 파서와 보수적 병합
│     ├─ source.js      직렬 GET, 조건부 요청, 지속 quota/circuit
│     ├─ aws.js         DynamoDB lease/state, SQS, Scheduler adapter
│     └─ workflow.js    일일 계획과 12시간 제한 경기 창
├─ test/                파서·설정·정책·병합 테스트
├─ Dockerfile           non-root, fixture dry-run 안전 기본 명령
├─ TARGET_SCOPE.md      KBO 전용 URL·필드·실행 주기 결정
├─ AWS_FARGATE_SETUP.md AWS 콘솔 배포·검증·중단 절차
├─ POLICY.md            수집 허용 기준과 데이터 범위
└─ OPERATIONS.md        실행, 부하 제어, 장애와 삭제 런북
```

세부 파일은 구현 과정에서 나뉠 수 있지만 처리 경계는 다음과 같다.

```text
YAML + profile + env + CLI
            │
            ▼
     설정 검증·비밀 마스킹
            │
            ▼
 정책 게이트 ── 실패 ──> 외부 요청 0건
            │ 통과
            ▼
 fixture 또는 승인된 provider 수집
            │
            ▼
 정규화 ──> 경기 매칭·병합 ──> 변경 감지 ──> JSON 출력
```

## 설정 파일과 프로필

기본 설정 파일은 `config/crawler.yml`이다. 공통값은 `base`, 프로필별 차이는 `profiles` 아래에 둔다.

```yaml
version: 1
defaultProfile: fixture

base:
  enabled: false
  source: fixture
  timezone: Asia/Seoul
  output:
    directory: out
    enabled: true
    dryRun: false
  policy:
    enforcement: strict
    killSwitch: true
    failClosed: true
    respectRobotsTxt: true

profiles:
  fixture:
    enabled: true
    source: fixture
    policy:
      killSwitch: false

  safe:
    enabled: false
    source: fixture
    output:
      enabled: false
    policy:
      killSwitch: true

  hybrid-locked:
    enabled: false
    source: hybrid
    policy:
      killSwitch: true
```

- `fixture`: 기본 프로필. 합성 fixture만 사용하고 외부 요청은 0건이다.
- `safe`: 모든 실행과 출력을 잠가 두는 점검용 프로필이다.
- `hybrid-locked`: 과거 KBO+NAVER 검토 흔적을 재현하기 위한 잠긴 프로필이다. 현재 제품 범위가 아니며 배포하지 않는다.
- Fargate 전용 `config/fargate.yml`의 `fixture`: 두 페이지 DOM을 모사한 로컬 HTML만 읽는다.
- Fargate 전용 `kbo-locked`: 실제 page-only adapter용이지만 서면 허가 값을 넣고 kill switch를 명시적으로 내리기 전까지 차단된다.

유효 설정의 우선순위는 낮은 쪽부터 다음과 같다.

```text
YAML base
  < YAML profiles.<selected>
  < --env-file로 읽은 값
  < 현재 프로세스 환경변수
  < 명시적 CLI 옵션과 --set
```

설정을 바꿀 때에는 파일을 직접 복제하기보다 프로필, 환경변수와 `--set`을 사용한다. 비밀은 YAML이나 Git에 넣지 않는다.

자주 바꾸는 값:

| 목적 | YAML 키 | 환경변수 | CLI |
| --- | --- | --- | --- |
| 프로필 | `defaultProfile` | `CRAWLER_PROFILE` | `--profile` |
| 대상 날짜 | `date` | `CRAWLER_DATE` | `--date` |
| 일정 최대 경기 수 | `run.maxGamesPerRun` | `CRAWLER_MAX_GAMES` | `--max-games` |
| 상세 최대 경기 수 | `run.maxDetailGamesPerRun` | `CRAWLER_MAX_DETAILS` | `--max-details` |
| 호스트별 요청 최소 간격 | `request.minHostIntervalMs` | `CRAWLER_MIN_HOST_INTERVAL` | `--set request.minHostIntervalMs=3s` |
| 요청 시간 제한 | `request.timeoutMs` | `CRAWLER_TIMEOUT` | `--set request.timeoutMs=15s` |
| 출력 디렉터리 | `output.directory` | `CRAWLER_OUTPUT_DIR` | `--out` |
| 쓰기 여부 | `output.enabled` | `CRAWLER_OUTPUT_ENABLED` | `--no-write` |
| 전체 외부 요청 중단 스위치 | `policy.killSwitch` | `CRAWLER_KILL_SWITCH` | `--set policy.killSwitch=true` |

기간 값은 `250ms`, `2s`, `15m`, `24h`, `7d`처럼 단위를 붙인다. 저장소의 [.env.example](./.env.example)을 복사해 로컬 환경 파일로 사용할 수 있지만 `.env`와 허가 증빙·비밀값은 커밋하지 않는다.

설정 묶음은 역할별로 나뉜다.

- `run`: 1회/예약 모드와 일정·상세 경기 상한
- `output`: 디렉터리, 파일 접두사, 변경 없는 결과 강제 쓰기, dry-run과 원자적 저장
- `request`: 시간 제한, 호스트별 최소 간격, 실행당 논리 요청·실제 시도·응답 크기 상한, 메모리 validator TTL, jitter, 재시도와 회로 차단
- `data`: 경기당 이벤트 수 상한과 relay 설명문 포함 여부. 설명문은 기본 `false`다.
- `strategy.polling`: 먼 미래·당일·경기 직전·진행·지연·종료 후 상태별 폴링 간격
- `policy`: strict 강제, kill switch, HTTPS·robots 필수, 검토 유효기간과 미문서 API 차단
- `providers`: 제공처 활성화, 호스트 allowlist, 공식 정책 URL, 승인 증빙·유효기간·scope와 endpoint

YAML에 없는 키, 범위를 벗어난 숫자, 잘못된 기간·날짜·cron, 프로젝트 밖 경로, 와일드카드 호스트, HTTP URL, 중복 CLI 덮어쓰기는 설정 단계에서 거부한다.
제공처의 host·endpoint 경로·robots·약관 manifest는 설정으로 교체할 수 없고 서면 범위 확인을 포함한 코드 검토가 필요하다. 요청 헤더는 `accept`와 `accept-language`만 조절할 수 있으며 User-Agent는 `identity`, 쿠키·인증 헤더는 금지된다.

### Fargate 설정

Fargate 경로는 `config/fargate.yml`의 안전 기본값과 다음 우선순위만 사용한다.

```text
fargate.yml base < profiles.<selected> < 허용 목록에 있는 환경변수 < CLI의 profile/date
```

알 수 없는 `CRAWLER_*` 환경변수는 오타로 보고 거부한다. URL은 환경변수 대상이 아니다. 자주 조절할 수 있는 값은 다음과 같다.

| 목적 | 환경변수 | 기본값/제약 |
| --- | --- | --- |
| 전체 중단 | `CRAWLER_KILL_SWITCH` | `true`; 실 요청 전 명시적으로 `false` 필요 |
| 일정 선행 실행 | `CRAWLER_PLAN_LEAD_MINUTES` | 60분 |
| 스코어보드 시작 | `CRAWLER_SCOREBOARD_LEAD_MINUTES` | 10분 |
| 일정/점수 간격 | `CRAWLER_SCHEDULE_REFRESH_MINUTES`, `CRAWLER_SCOREBOARD_REFRESH_MINUTES` | 15분, 2분보다 짧게 설정 불가 |
| 종료 보정 | `CRAWLER_FINAL_CHECK_MINUTES` | `5,30,90`, 오름차순 3개 |
| 하드 타임아웃 | `CRAWLER_HARD_TIMEOUT_MINUTES` | 최대 720분 |
| 지속 요청량 | `CRAWLER_MAX_LOGICAL_REQUESTS_PER_HOUR`, `CRAWLER_MAX_ATTEMPTS_PER_HOUR` | DynamoDB에서 모든 Task 합산 |
| AWS 연결 | `CRAWLER_STATE_TABLE`, `CRAWLER_SNAPSHOT_QUEUE_URL`, `CRAWLER_SCHEDULER_*`, `CRAWLER_ECS_*` | 실 실행에서 모두 필수 |

승인 관련 `CRAWLER_AUTH_*` 값은 허가서의 식별자, 검토·만료 시각과 `schedule-page,scoreboard-page,robots-disallow-override` scope를 그대로 반영해야 한다. 증빙 원문이나 개인정보는 환경변수와 Git에 넣지 않는다.

Fargate 결과는 DynamoDB의 날짜별 상태와 lease, 시간당 요청 quota를 사용한다. SQS Standard queue 메시지에는 내용 지문 기반 `idempotencyKey`가 포함되지만 at-least-once 전달 자체는 가능하므로 소비자도 이 키로 멱등 처리한다. 원본 HTML은 DynamoDB, SQS 또는 로그에 저장하지 않는다.

## CLI

정확한 전체 옵션과 현재 기본값은 항상 도움말을 기준으로 한다.

```bash
npm run crawl -- --help
```

지원하는 플래그:

| 구분 | 플래그 |
| --- | --- |
| 설정 선택 | `--config PATH`, `--profile NAME`, `--env-file PATH`, 반복 가능한 `--set PATH=VALUE` |
| 실행 범위 | `--source SOURCE`, `--date YYYY-MM-DD`, `--max-games N`, `--max-details N`, `--out PATH` |
| 실행 방식 | `--once`, `--cron[="EXPR"]`, `--scheduled[="EXPR"]` |
| 출력 제어 | `--force-write`, `--dry-run`, `--no-write` |
| 검사 | `--print-config`, `--check-config`, `--check-policy`, `--help` |

날짜는 실제 달력에 존재하는 `YYYY-MM-DD`, cron은 초 필드가 없는 정확한 5필드 형식만 허용한다. 출력 및 fixture 경로는 이 크롤러 프로젝트 내부로 제한한다. 같은 설정 키를 두 개의 CLI 옵션으로 중복 지정하거나 `--once`와 `--scheduled`를 함께 지정하면 오류로 종료한다.
KBO 실 소스는 경기일 경계를 일관되게 해석하기 위해 `timezone=Asia/Seoul`만 허용한다.

일반적인 검증 흐름:

```bash
# 스키마, 타입, 범위 검증
npm run config:check -- --profile fixture

# 병합된 최종 설정 출력(비밀값 마스킹)
npm run config:print -- --profile fixture

# 로컬 설정에 기록된 정책·허가·robots 예외 조건 검증
npm run policy:check -- --profile fixture

# 파일 저장과 외부 요청 없이 실행 계획 확인
npm run crawl -- --profile fixture --dry-run

# 1회 실행(`crawl` 스크립트가 --once를 이미 지정함)
npm run crawl -- --profile fixture
```

설정 파일·환경 파일·날짜·출력 위치를 한 번만 바꾸는 예:

```powershell
npm run crawl -- --config ./config/crawler.yml --profile fixture --env-file ./.env.local --date 2026-08-25 --out ./out --max-games 5 --dry-run
```

점 표기 경로를 지원하는 설정은 `--set key=value`로 덮어쓸 수 있다. 배열·숫자·불리언 변환 방식은 `--help`와 `--print-config` 결과로 확인한다.

```bash
npm run crawl -- --profile fixture --set request.timeoutMs=15s --dry-run
```

CLI 값은 가장 높은 우선순위를 가지므로 운영 스크립트에서는 누가 어떤 값을 덮어썼는지 로그와 변경 기록을 남긴다.

`--check-policy` 자체는 외부 요청을 만들지 않고 설정과 내장 정책 카탈로그를 검사한다. 실 제공처의 현재 robots.txt는 유효한 서면 허가로 모든 사전 게이트를 통과한 실행에서, 대상 URL을 요청하기 직전에 다시 검사한다. `--dry-run`도 정책 게이트는 통과해야 하며 날짜·소스·예상 일정/상세 요청 상한·쓰기 여부·출력 경로만 JSON 계획으로 보여준다.

## 예약 실행

fixture로 스케줄 동작을 검증한 뒤에만 예약 실행을 사용한다.

```bash
npm run crawl:schedule -- --profile fixture
```

실 네트워크 예약은 [OPERATIONS.md](./OPERATIONS.md)의 시작 전 체크리스트와 kill switch를 준비하고, 매 실행 전에 허가 만료·robots·정책 상태를 확인하도록 구성해야 한다. 401·403·429·CAPTCHA·robots 변경은 자동 재시도 대상이 아니라 중단 신호다.
실 네트워크 예약에서 `--no-write`는 금지된다. 상태 파일이 없으면 재시작 뒤 적응형 폴링 간격을 보존할 수 없기 때문이다.

AWS에서는 매일 짧은 계획 Task가 일정 페이지를 한 번 확인하고, 경기가 있을 때만 일회성 Fargate 경기 Task를 예약한다. 정확한 주기와 콘솔 설정은 [TARGET_SCOPE.md](./TARGET_SCOPE.md)와 [AWS_FARGATE_SETUP.md](./AWS_FARGATE_SETUP.md)를 따른다.

## 출력 스키마

최상위 출력은 실행 메타데이터, 요약과 정규화된 `games` 배열을 가진 JSON이다. 구현 버전에 따라 부가 필드가 추가될 수 있으므로 소비자는 알 수 없는 필드를 무시해야 한다.

기본 출력은 `out/games-YYYY-MM-DD.json`, 변경 감지·폴링 상태는 `out/.crawler-state.json`이다. 동시 실행은 `out/.crawler.lock`으로 막고, JSON은 임시 파일을 같은 디렉터리에 쓴 뒤 rename하는 방식으로 교체한다. 관측 시각처럼 매번 변하는 값은 내용 지문에서 제외하므로 경기 내용이 같으면 스냅샷 파일을 다시 쓰지 않는다. `--force-write`는 이 최적화만 무시하며 정책 게이트나 요청 한도를 우회하지 않는다.

최상위 주요 필드:

| 필드 | 의미 |
| --- | --- |
| `schemaVersion` | 현재 스냅샷 형식 버전 `1` |
| `source`, `date`, `crawledAt` | 실행 소스, 대상 경기일, 관측 시각 |
| `partial`, `providerErrors`, `anomalies` | 일부 제공처 실패 여부와 오류·매칭 진단 |
| `total`, `statusCounts`, `sourceCounts` | 경기 수 요약 |
| `games` | 아래 정규형 경기 배열 |
| `requestMetrics` | 논리 요청, 실제 HTTP 시도와 회로 상태. fixture에서는 모두 0 |
| `contentFingerprint` | 저장 시 계산한 내용 지문 |

경기 객체의 정규형:

```json
{
  "date": "2026-08-25",
  "gameNumber": 1,
  "source": { "kbo": false, "naver": false },
  "externalId": { "kbo": null, "naver": null },
  "scheduledAt": "2026-08-25T09:30:00.000Z",
  "startedAt": null,
  "endedAt": null,
  "homeTeam": { "name": "홈팀", "code": "HOME" },
  "awayTeam": { "name": "원정팀", "code": "AWAY" },
  "stadium": "예시 구장",
  "status": "SCHEDULED",
  "statusText": "경기 예정",
  "score": { "home": null, "away": null },
  "inning": null,
  "half": null,
  "events": [],
  "meta": { "observedAt": "2026-08-25T09:00:00.000Z" }
}
```

핵심 필드 의미:

| 필드 | 의미 |
| --- | --- |
| `date`, `scheduledAt` | 경기 일자와 예정 시각 |
| `homeTeam`, `awayTeam` | 홈·원정팀 이름과 내부 정규화 코드 |
| `stadium` | 구장 텍스트 이름 |
| `status`, `statusText` | 정규 상태와 제공처의 상태 표현 |
| `score.home`, `score.away` | 홈·원정 점수 |
| `inning`, `half` | 현재 이닝과 `TOP`/`BOTTOM` |
| `startedAt`, `endedAt` | 확인 가능한 실제 시작·종료 시각 |
| `events` | 기본 빈 배열. 명시적으로 허가된 최소 정규화 이벤트만 포함 |
| `source`, `externalId`, `meta` | 출처 추적, 외부 식별자와 관측 메타데이터 |

시각은 제공처의 KST 값을 해석한 뒤 UTC ISO 8601 문자열로 정규화한다. fixture 경기의 `source`는 두 실 제공처가 모두 `false`다.

정규형에 남아 있는 NAVER provenance 필드는 기존 스키마 호환용이며 KBO 전용 운영에서는 항상 비어 있어야 한다. 과거 KBO·NAVER 병합 계층은 새 KBO 전용 adapter가 안정화되면 별도 변경으로 제거한다.

원문 relay 문장, 전체 응답, 로고, 사진, 영상과 인증 정보는 출력 스키마에 포함하지 않는다.

## 부하 제어 원칙

- 호스트별 동시성 1, 동일 URL·경기 요청 중복 제거
- 경기 상태별 적응형 폴링과 종료 후 상세 조회 중단
- `ETag`·`Last-Modified` 조건부 요청 및 변경 없는 파일 쓰기 생략
- 제한된 지수 백오프와 jitter, `Retry-After` 우선 준수
- 401·403은 재시도하지 않고 제공처 중지
- 429는 같은 호출도 자동 재시도하지 않고 `Retry-After` 이상 회로를 열며, CAPTCHA·robots·약관 변경 시 전체 실 네트워크 중지
- 프록시/IP 회전, CAPTCHA 우회, 로그인 자동화, 내부 URL 추측 금지

자세한 운영 기준과 삭제 요청 절차는 [OPERATIONS.md](./OPERATIONS.md)를 따른다.
