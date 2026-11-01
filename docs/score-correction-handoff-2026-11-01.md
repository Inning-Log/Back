# 기록 점수 후보정용 이력 API · 프론트 전달

## 이번 변경

KBO 스코어보드의 기존 경기 중 15초 수집을 유지하면서, 점수 변경 전후의 **관측 시각**을 제공한다. 새로운 사이트나 경기별 상세 중계 요청을 운영 수집에 추가하지 않았다. 같은 점수·상태는 기존처럼 SQS·DB 이력에 반복 저장하지 않는다.

오늘(2026-11-01) 경기의 AWS 관측 로그를 확인한 결과 LIVE 수집 간격 중앙값은 15.15초, 최대는 16.33초였다. 원본 점수에 4→3→4로 되돌아오는 구간도 있어 점수 감소·정정을 일반 득점과 구분한다. 이 수치는 수집 간격이며 TVING 화면 대비 지연 측정값은 아니다.

## 호출

`GET /api/games/{gameId}/score-changes`

기존 로그인 Bearer 토큰을 사용한다. 게임 ID 오류 400, 미인증 401, 없는 경기 404. 한 경기의 이력을 한 번 받아 같은 경기의 여러 기록에 재사용한다. 기존 `/states` 응답과 기록 저장 응답은 그대로 사용할 수 있다.

아래 값은 이해를 위한 합성 예시다.

```json
{
  "gameId": 123,
  "timeBasis": "OBSERVED",
  "actualEventTimeAvailable": false,
  "automaticCorrectionAvailable": false,
  "changes": [
    {
      "stateId": 456,
      "type": "SCORE_INCREASE",
      "inning": 2,
      "half": "BOTTOM",
      "before": { "homeScore": 1, "awayScore": 0 },
      "after": { "homeScore": 3, "awayScore": 0 },
      "previousScoreObservedAt": "2026-11-01T05:35:15Z",
      "observedAt": "2026-11-01T05:35:30Z",
      "observationGapMillis": 15000,
      "occurredAt": null,
      "source": "SCOREBOARD"
    }
  ]
}
```

| 필드 | 의미 |
| --- | --- |
| `stateId` | 해당 변경의 서버 이력 ID. 프론트 항목 키로 사용 |
| `type` | `INITIAL_OBSERVATION`: 최초 확인 점수 / `BASELINE_AFTER_GAP`: 점수 미확인 구간 뒤 기준점 / `SCORE_INCREASE`: 증가 / `SCORE_CORRECTION`: 감소가 포함된 정정 |
| `before`, `after` | 홈·원정 기준 변경 전후 점수. 최초·단절 뒤에는 `before=null` |
| `previousScoreObservedAt` | 변경 전 점수를 마지막으로 확인한 시각. 같은 점수가 오래 유지됐더라도 직전 실제 관측을 사용 |
| `observedAt` | 변경 후 점수를 확인한 시각 |
| `observationGapMillis` | 앞뒤 관측 간격. 원본 자체 갱신 지연이나 방송 지연은 계산하지 않음 |
| `occurredAt` | 실제 득점 시각. 확인 가능한 근거가 없어 현재 `null` |
| `source` | `SCOREBOARD` 또는 `SCHEDULE`. 일정에서 들어온 결과에는 정밀한 스코어보드 관측 구간이 없을 수 있음 |

이력은 관측 시각·ID 순서다. 이닝만 바뀐 상태는 점수 변경 목록에 중복 표시하지 않는다. 한 번에 2점이 늘면 2점 증가 한 건이며, 실제 득점 플레이 두 개로 임의 분리하지 않는다. 점수 감소 후 다시 증가한 경우도 원본 순서대로 제공하므로 `SCORE_INCREASE`만 골라 새 득점으로 단정하면 안 된다.

## 프론트 적용

1. 기록은 기존 `recordedAt`, `homeScore`, `awayScore`, `scoreObservedAt`을 유지한다. 저장 직후에는 생성 응답을 사용한다.
2. 해당 경기의 `score-changes`를 조회해 기록 시각 주변에 다른 점수가 관측됐는지 비교한다. 같은 경기·이닝·초말인지 함께 확인한다. 최초 관측과 관측 단절은 새 득점으로 처리하지 않는다.
3. `previousScoreObservedAt`부터 `observedAt`까지는 **원본에서 점수 변경을 확인한 관측 구간**이다. 실제 득점은 KBO 갱신 지연 때문에 이보다 먼저 일어났을 수 있다. 이 구간에 기록이 있다는 이유만으로 `after`를 정답으로 자동 확정하지 않는다.
4. 현재 `automaticCorrectionAvailable=false`이므로 전후 점수를 비교하는 후보정 안내·검토 자료로 사용한다. 원본 정정이나 넓은 수집 공백에서는 특히 확정 보정을 하지 않는다. 일괄적으로 15초 또는 방송 지연 추정치를 빼지 않는다.
5. 화면이 보이고 경기 진행 중일 때만 10~15초 재조회하며 중복 요청을 막는다. 여러 기록별로 같은 API를 각각 호출하지 않는다. 화면 복귀 시 재조회하고 숨김·이탈·종료 시 중단하거나 느리게 전환한다.
6. UTC ISO 시각으로 비교하고 표시는 KST로 변환한다. nullable 점수는 0:0으로 바꾸지 않는다.

이번 서버 변경은 이력 조회 기능이다. 기존 기록 점수의 자동 재작성이나 사용자 확정 점수 저장 API는 포함하지 않는다. 실제 득점 시각이 없는 상태에서 자동 보정을 약속하면 역전 전 촬영도 역전 후 점수로 바뀔 수 있다.

## 과거 이력·예외

- 도입 전 이력은 변경 시각만 보관했으므로 `previousScoreObservedAt=null`일 수 있다. 기존 변경 시각에서 임의로 15초를 빼서 채우지 않는다.
- 크롤러 재시작, 수집 누락, 같은 경기 확인 실패, 오래된 응답, 앞뒤 점수 불일치에서는 관측 구간을 생략한다.
- 실제 관측 로그로 앞뒤 시각·점수를 검증할 수 있는 경우에만 과거 구간을 보완할 수 있다. 촬영 기록 점수 자체는 수정하지 않는다.

## 소스 확인 근거

- [KBO 스코어보드](https://www.koreabaseball.com/Schedule/ScoreBoard.aspx): 현재 운영 소스. 점수·이닝·상태는 확인되지만 득점별 발생 시각은 현재 파서 입력에 없다.
- [네이버 11월 1일 경기 중계](https://m.sports.naver.com/game/55551101HTSS02026/relay): 실제 화면과 화면 코드에 명시된 중계 응답(현재 이닝 및 2회)을 확인했다. `textOptions.currentGameState` 등에 점수는 있으나 확인한 응답에는 명시적인 득점 발생 시각 필드가 없었다. 이름·번호로 시각을 추측하거나 네이버가 더 빠르다고 가정하지 않았다.

따라서 이번에는 추가 수집 비용 없이 KBO 관측 근거를 강화하는 방식을 선택했다.
