# 타임라인·이닝 기록 API

구현일: 2026-09-14. 경기별 관람 기록 위에 이닝 기록을 저장하고, 본인 또는 현재 수락한 친구가 조회한다. 같은 이닝·초말의 기록 여러 개를 허용한다.

## 프론트와 맞출 주소 규칙

프론트 저장소 `Inning-Log/Front`의 `main`을 확인했다. 분석 기준 커밋은 `dc2ac4ae1e477242a4f910f0b7c0ca4fa108f44f`다. 프론트 수정은 이 백엔드 브랜치에 포함하지 않는다.

친구 화면은 **경로의 숫자 userId**로 통일한다. 누구의 화면인지 경로에 두고, 선택한 경기는 gameId로 지정한다. `username` 또는 `@아이디`를 숫자 userId 대신 보내지 않는다.

| 진입 | 프론트 화면 주소 | 호출할 API |
| --- | --- | --- |
| 내 현재 경기 | `/timeline?gameId=123` | `GET /api/timelines/me?gameId=123` |
| 12번 친구의 경기 | `/timeline/12?gameId=123` | `GET /api/timelines/12?gameId=123` |
| 달력에서 지난 경기 선택 | `/timeline/history/123` | `GET /api/timelines/me?gameId=123` |

프론트 연동 시 필요한 변경:

1. [MainPage](https://github.com/Inning-Log/Front/blob/dc2ac4ae1e477242a4f910f0b7c0ca4fa108f44f/src/pages/Home/MainPage.tsx#L70): 날짜별 `games`에서 선택한 gameId를 전달한다. 여러 경기면 사용자가 고른 경기로 이동한다. 날짜를 gameId 자리에 넣지 않는다.
2. [TimelineProfileList](https://github.com/Inning-Log/Front/blob/dc2ac4ae1e477242a4f910f0b7c0ca4fa108f44f/src/features/timeline/components/TimelineProfileList.tsx#L123): 친구 클릭 주소를 `/timeline/{숫자 userId}?gameId=...`로 변경한다. 선택 프로필 표시도 `useParams().userId`를 읽도록 통일한다. 친구가 다른 경기를 관람했다면 프로필 응답의 `games`에서 해당 친구의 경기를 선택한다.
3. [TimelinePage](https://github.com/Inning-Log/Front/blob/dc2ac4ae1e477242a4f910f0b7c0ca4fa108f44f/src/pages/Timeline/TimelinePage.tsx#L74): userId는 경로, gameId는 history 경로 또는 query에서 읽는다. 수정 가능 여부는 서버 `canCreateRecord`를 사용한다.
4. [RecordPage](https://github.com/Inning-Log/Front/blob/dc2ac4ae1e477242a4f910f0b7c0ca4fa108f44f/src/pages/Record/RecordPage.tsx#L69): 날짜별 localStorage를 관람 여부 판단의 기준으로 사용하지 않는다. 선택 경기의 `myViewing`이 없으면 관람 API로 등록한다. `stadium/home`을 서버 `STADIUM/HOME`으로 변환한다. 새 기록용 UUID를 한 번 생성하고 요청 재시도에는 같은 값을 유지한다.

화면 주소와 API 주소는 다른 주소다. Spring/Vercel 설정만으로 프론트가 읽는 경로 변수나 localStorage 로직을 변경할 수 없다.

## 인증과 권한

모든 API는 서비스 JWT가 필요하다. 현재 사용자는 활성 상태이고 온보딩을 완료해야 한다. 기록 생성·문구 수정·삭제의 작성자는 JWT로 결정한다.

- 타임라인·기록 상세: 본인 또는 현재 `ACCEPTED` 친구만 열람한다. 친구 방향은 무관하다.
- 친구 요청 대기·거절·친구 삭제·탈퇴 이후에는 열람할 수 없다. 응답은 404다.
- 문구 수정과 삭제: 작성자만 가능하다. 친구에게 쓰기 권한을 주지 않는다.
- 프로필 목록: 내 프로필과 수락한 활성 친구만 포함한다. 이메일이나 JWT는 반환하지 않는다.

## API 목록

| 메서드·경로 | 의미 | 성공 |
| --- | --- | --- |
| `GET /api/timelines/me?gameId=123` | 내 경기 타임라인 | 200 |
| `GET /api/timelines/{userId}?gameId=123` | 지정 사용자의 경기 타임라인 | 200 |
| `GET /api/timelines/profiles` | 내 프로필·친구·오늘 관람 경기 목록 | 200 |
| `POST /api/inning-records` | 내 이닝 기록 생성 | 201, 동일 재시도 200 |
| `GET /api/inning-records/{id}` | 기록 상세 | 200 |
| `PATCH /api/inning-records/{id}` | 내 기록 문구 수정 | 200 |
| `DELETE /api/inning-records/{id}` | 내 기록 소프트 삭제 | 204 |

타임라인 gameId와 경로 userId/id는 양의 정수다. 날짜나 공개 사용자 이름을 대신 보내면 400이다. Swagger와 `/v3/api-docs`에 동일 계약을 제공한다.

## 경기 타임라인 응답

```json
{
  "today": "2026-09-14",
  "timezone": "Asia/Seoul",
  "owner": {"userId": 12, "username": "inning.fan", "nickname": "야구팬", "profileImageUrl": null},
  "game": {"gameId": 123},
  "viewing": {"id": 456, "viewingType": "STADIUM", "cheeringTeamId": 5},
  "currentInning": 3,
  "currentHalf": "TOP",
  "canCreateRecord": true,
  "requiresViewingRegistration": false,
  "recordCount": 1,
  "records": [{
    "id": 789,
    "gameId": 123,
    "userGameLogId": 456,
    "clientRecordId": "22cbd48a-0f2c-40e7-891d-3e2451398c5c",
    "inning": 3,
    "half": "TOP",
    "recordedAt": "2026-09-14T09:45:00Z",
    "text": "오늘의 관람 기록",
    "homeScore": 1,
    "awayScore": 0,
    "scoreObservedAt": "2026-09-14T09:44:50Z",
    "videoStatus": "UNAVAILABLE",
    "createdAt": "2026-09-14T09:45:01Z",
    "updatedAt": "2026-09-14T09:45:01Z"
  }],
  "nextCursor": null
}
```

예시는 합성 데이터이며 `game`을 축약했다. 실제 game은 [홈·경기 API](home-game-api.md)의 전체 경기 응답이며 `recordCount`, `hasRecords`도 포함한다.

- `owner`: 현재 보고 있는 타임라인의 작성자.
- `viewing`: 그 작성자의 관람 등록. 등록이 없으면 null이며 records는 빈 배열이다.
- `game.myViewing`, `game.recordCount`, `game.hasRecords`: 항상 **JWT 사용자 본인** 기준이다. 친구 화면에서는 최상위 `viewing`/`recordCount`와 구분한다.
- `currentInning/currentHalf`: 수집된 현재 진행 정보. 모르면 null이며 프론트에서 1회로 임의 치환하지 않는다.
- `canCreateRecord`: 본인 + KST 당일 경기 + 활성 관람 등록 + 취소/연기 아님.
- `requiresViewingRegistration`: 본인의 기록 가능한 당일 경기에서 관람 등록이 아직 없음.
- 과거 타임라인도 같은 API를 쓰며 새 기록 생성은 불가능하다. 기존 문구 수정·삭제는 작성자가 할 수 있다.

### 기록 페이지

`limit`은 기본 50, 범위 1~100이다. `cursor`는 응답의 nextCursor를 그대로 보낸다. gameId/userId는 유지한다.

정렬은 `(inning, recordedAt, id)` 오름차순이며 이닝 값으로 프론트에서 그룹화한다. 한 이닝의 기록이 여러 페이지에 나뉠 수 있으므로 ID로 중복 제거하며 합친다. `recordCount`는 전체 활성 기록 수이고 `records.length`는 현재 페이지 크기다. nextCursor가 null이면 마지막 페이지다.

커서는 특정 사용자·경기에 묶여 있다. 형식 오류나 다른 타임라인 커서는 400이다. 경계 기록이 삭제되어도 다음 페이지를 읽을 수 있다. 페이지를 넘기는 동안 이전 정렬 위치에 새 기록이 추가될 수 있으므로, 새 기록 작성 후에는 첫 페이지부터 다시 조회한다. 여러 페이지를 고정 시점의 스냅샷으로 보장하지 않는다.

## 프로필 목록

`GET /api/timelines/profiles?limit=50&afterUserId=0`

```json
{
  "today": "2026-09-14",
  "me": {"userId": 12, "username": "inning.fan", "nickname": "야구팬", "profileImageUrl": null, "hasRecordedToday": true,
    "games": [{"gameId": 123, "viewingType": "STADIUM", "recordCount": 2}]},
  "friends": [],
  "nextAfterUserId": null
}
```

친구는 숫자 userId 오름차순으로 페이지 처리한다. 다음 페이지는 nextAfterUserId를 afterUserId에 전달한다. me는 매 응답에 포함되므로 중복 추가하지 않는다. limit은 기본 50, 최대 100이다.

각 games는 해당 사용자가 오늘 등록한 활성 관람 경기 목록이다. 더블헤더는 서로 다른 gameId로 반환한다. 관람 등록만 하고 이닝 기록이 없으면 recordCount=0이다. `hasRecordedToday`는 활성 이닝 기록이 실제로 있는지를 뜻한다. 영상 준비 여부를 뜻하지 않는다.

## 기록 생성·정정·삭제

먼저 [관람 API](home-game-api.md)로 해당 gameId의 직관/집관을 등록한다. 그 다음:

```json
{
  "gameId": 123,
  "clientRecordId": "22cbd48a-0f2c-40e7-891d-3e2451398c5c",
  "inning": 3,
  "half": "TOP",
  "recordedAt": "2026-09-14T09:45:00Z",
  "text": "오늘의 관람 기록"
}
```

- inning: 1~99. 연장 이닝 허용. half: TOP/BOTTOM 또는 null(미확인).
- recordedAt: 날짜와 UTC offset을 포함한 ISO 시각. KST로 경기일과 같고 서버 현재 시각 이후가 아니어야 한다. DB와 동일하게 마이크로초까지 보관한다. `18:43` 같은 표시용 시각만 보내지 않는다.
- text: 필수 문자열, 최대 255자. 빈 문자열은 허용한다.
- clientRecordId: UUID. 같은 경기에서 동일 최초 요청을 재전송하면 같은 기록 ID와 200을 반환한다. 서로 다른 기록에는 새 UUID를 사용한다. 같은 UUID의 내용 변경은 409다. 문구 정정 이후 최초 요청의 재시도는 현재 기록을 반환하며 문구를 되돌리지 않는다.
- 이미 저장된 요청은 자정이 지나거나 경기 상태가 바뀌어도 동일 재시도가 성공한다. 삭제된 기록의 재시도는 409이며 자동 복원하지 않는다.
- 본문에 사용자 ID나 점수를 추가해도 작성자·점수로 사용하지 않는다. 서버가 JWT와 경기 관측으로 결정한다.

점수는 촬영 시각 **이전 또는 동일 시각에 관측했고 5분 이내인** 서버 경기 결과만 복사한다. LIVE/FINISHED이며 양쪽 점수가 확인되어야 한다. 이후 관측값·오래된 값·미확인 점수만 있으면 점수와 scoreObservedAt은 모두 null이다. 이 값은 경기 관측 스냅샷이며 촬영 순간의 정확한 공식 점수를 보증하지 않는다. 이후 경기 점수 정정은 이미 저장된 기록 스냅샷을 바꾸지 않는다.

PATCH 본문:

```json
{ "text": "수정한 문구" }
```

문구만 바꾸고 경기/이닝/촬영 시각/점수는 유지한다. DELETE는 소프트 삭제하며 같은 삭제 재시도도 204다.

- 이닝 기록 삭제는 관람 등록이나 승률을 바꾸지 않는다.
- 부모 관람 기록 삭제는 그 아래 이닝 기록도 같은 트랜잭션에서 소프트 삭제한다. 관람을 복원해도 이전 이닝 기록은 복원하지 않는다.
- 탈퇴 시 이닝 기록도 소프트 삭제되어 친구 화면과 프로필에서 제외된다.
- 홈 달력과 날짜별 경기의 `recordCount/hasRecords`에 활성 이닝 기록 수가 반영된다. 관람 기록만 있다고 hasRecords=true가 되지 않는다.

## 오류

형식은 `{code, message, timestamp}`다. 인증 실패 401은 기존 인증 응답을 따른다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 필수값, ID/날짜/UUID/이닝/문구/커서 검증 오류 |
| 404 | TIMELINE_NOT_FOUND | 대상 사용자 없음·비활성 또는 친구 열람 권한 없음 |
| 404 | RECORD_NOT_FOUND | 기록 없음·삭제됨 또는 쓰기 소유권 없음 |
| 404 | GAME_NOT_FOUND | 조회할 경기 없음 |
| 409 | VIEWING_REQUIRED | 해당 경기의 활성 관람 등록 필요 |
| 409 | RECORDING_DATE_NOT_TODAY | 당일 이외 새 기록 생성 |
| 409 | GAME_NOT_RECORDABLE | 취소·연기 경기 |
| 409 | RECORD_REQUEST_CONFLICT | 같은 clientRecordId의 다른 최초 요청 |
| 409 | RECORD_DELETED | 삭제한 기록 재전송 |
| 409 | PROFILE_SETUP_REQUIRED / ACCOUNT_DELETED | 기존 계정·온보딩 검증 |

## DB와 구현 범위

Flyway V16이 inning_records를 만든다. `user_game_logs 1:N inning_records`이며 `(user_game_log_id, client_record_id)`만 요청 중복을 제한한다. 이닝/초말 조합은 UNIQUE가 아니다. 목록은 한 페이지로 제한하고 홈 기록 개수·프로필 경기 목록은 일괄 집계한다.

이번 API는 타임라인 데이터와 기록 메타데이터를 실제로 저장·조회한다. 영상 파일 업로드/다운로드/삭제, 썸네일 생성, 내 영상/친구 영상 합성, 반응·댓글은 이 API의 구현 범위에 포함하지 않는다. `videoStatus=UNAVAILABLE`이며 실제 영상이 있다는 표시나 저장 완료 토스트를 이 응답만으로 보여주지 않는다. 영상 업로드 API를 연결할 때 파일 소유권·READY 상태·친구 공유 권한을 별도로 검증해야 한다.

로컬 테스트: `./gradlew test --tests 'com.inninglog.domain.game.TimelineApiIntegrationTest'`. 실제 PostgreSQL 검증은 [격리 테스트 DB 설정](home-game-api.md#로컬-확인)을 사용한다.

### 검증 결과 (2026-09-14)

- 전체 백엔드 테스트: 142개 통과. 타임라인 통합 테스트 14개 포함, 실패/건너뜀 0개.
- 격리 PostgreSQL 17.11에서 경기·타임라인 테스트 37개 재실행 통과.
- 실행 JAR + 별도 PostgreSQL DB + 실제 JWT/HTTP 요청 28개 확인 통과. 기록 생성/재시도/다중 이닝 기록, 페이지/상세/문구 정정, 달력 반영, 친구 요청 대기 시 차단, 수락 후 열람, 친구 쓰기 차단, 친구 삭제 후 재차단, 기록/관람 삭제를 확인했다.
- 검증에는 합성 계정과 경기를 사용했다. 운영 배포·실제 영상 처리·프론트 실행 검증은 포함하지 않는다.
