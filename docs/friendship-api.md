# Friendship API

친구 검색, 신청, 수락·거절, 친구 목록, 관계 삭제를 제공한다. 이후 공동 영상이나 기록 공유 기능은 이 도메인의 `ACCEPTED` 관계를 서버에서 다시 검증한 뒤 참여자를 확정해야 한다.

## 공통 규칙

- 모든 API는 Bearer JWT가 필요하다.
- 온보딩을 완료한 활성 사용자만 친구 기능을 사용할 수 있다.
- 검색 결과와 친구 응답에는 이메일을 포함하지 않는다.
- 사용자 아이디는 소문자로 정규화하며 앞부분 일치 검색을 한다.
- 검색 결과는 정확히 일치하는 아이디를 먼저 두고 최대 20명만 반환한다.
- 탈퇴 사용자, 온보딩 미완료 사용자, 자기 자신은 검색되지 않는다.
- 관계 ID를 모르는 제3자에게는 실제 존재 여부를 노출하지 않고 `FRIENDSHIP_NOT_FOUND`를 반환한다.

## 상태 모델

```text
관계 없음 ──신청──> PENDING ──수락──> ACCEPTED
                         └──거절──> REJECTED ──재신청──> PENDING
```

- 한 사용자 쌍에는 방향과 무관하게 하나의 관계 행만 존재한다.
- `pairKey`는 `minUserId:maxUserId` 형식이다.
- 신청 생성 시 두 사용자 행을 ID 오름차순으로 잠가 A→B와 B→A 동시 요청도 하나만 성공시킨다.
- `REJECTED` 관계를 재신청하면 같은 행을 사용하고 `requestRevision`을 증가시킨다.
- 재신청 방향이 반대라면 requester와 receiver를 새 방향으로 교체한다.
- `ACCEPTED` 관계 삭제와 요청자가 취소한 `PENDING` 신청은 물리 삭제한다.
- 받은 `PENDING` 신청은 DELETE로 지울 수 없고 accept 또는 reject를 사용해야 한다.
- `REJECTED` 이력은 재신청 주기를 구분하는 데 사용하므로 DELETE할 수 없다.

## 검색 관계 상태

`GET /api/users/search`는 검색된 사용자와 현재 사용자의 관계를 함께 반환한다.

| 값 | 의미 |
|---|---|
| `NONE` | 관계가 없거나 과거 요청이 거절돼 새 신청 가능 |
| `REQUEST_SENT` | 현재 사용자가 보낸 요청이 대기 중 |
| `REQUEST_RECEIVED` | 상대가 보낸 요청이 대기 중 |
| `FRIEND` | 수락된 친구 관계 |

## API

### 사용자 검색

```http
GET /api/users/search?username=inning
Authorization: Bearer {accessToken}
```

```json
[
  {
    "user": {
      "id": 12,
      "username": "inninglog",
      "nickname": "이닝로그",
      "profileImageUrl": "https://cdn.example.com/profile.png",
      "favoriteTeam": {
        "id": 1,
        "teamCode": "LG",
        "name": "LG 트윈스",
        "shortName": "트윈스",
        "logoUrl": null,
        "primaryColor": "#C30452",
        "displayOrder": 1
      }
    },
    "relationshipStatus": "NONE",
    "friendshipId": null
  }
]
```

### 친구 신청

```http
POST /api/friendships/requests
Authorization: Bearer {accessToken}
Content-Type: application/json

{
  "receiverId": 12
}
```

성공 시 `201 Created`와 생성된 관계 본문을 반환한다.

```json
{
  "id": 31,
  "status": "PENDING",
  "friend": {
    "id": 12,
    "username": "inninglog",
    "nickname": "이닝로그",
    "profileImageUrl": null,
    "favoriteTeam": null
  },
  "requestedByMe": true,
  "requestedAt": "2026-08-24T07:00:00Z",
  "respondedAt": null
}
```

### 친구 목록

```http
GET /api/friendships
Authorization: Bearer {accessToken}
```

`ACCEPTED` 관계만 최근 수락 순으로 반환한다. 공동 저장 화면은 이 목록을 공유 대상 후보로 사용할 수 있다.

### 받은 신청 목록

```http
GET /api/friendships/requests
Authorization: Bearer {accessToken}
```

현재 사용자가 receiver인 `PENDING` 요청만 최근 신청 순으로 반환한다.

### 신청 수락

```http
POST /api/friendships/{id}/accept
Authorization: Bearer {accessToken}
```

receiver만 실행할 수 있다. 동일 수락 요청을 재시도하면 현재 `ACCEPTED` 결과를 반환하고 알림은 중복 생성하지 않는다.

### 신청 거절

```http
POST /api/friendships/{id}/reject
Authorization: Bearer {accessToken}
```

receiver만 실행할 수 있다. 동일 거절 요청을 재시도하면 현재 `REJECTED` 결과를 반환한다.

### 친구 삭제 또는 보낸 신청 취소

```http
DELETE /api/friendships/{id}
Authorization: Bearer {accessToken}
```

- `ACCEPTED`: 어느 참여자든 삭제 가능
- `PENDING`: requester만 취소 가능
- 받은 `PENDING`: `403 FRIENDSHIP_ACTION_NOT_ALLOWED`
- `REJECTED`: `409 INVALID_FRIENDSHIP_STATE`
- 성공: `204 No Content`

## 충돌 응답

| HTTP | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 검색어, 관계 ID, 본문 형식 오류 |
| 403 | `FRIENDSHIP_ACTION_NOT_ALLOWED` | 요청자가 accept/reject하거나 수신자가 DELETE로 요청을 제거하려 함 |
| 404 | `USER_NOT_FOUND` | 인증 사용자가 존재하지 않음 |
| 404 | `FRIEND_USER_NOT_FOUND` | 상대가 없거나 탈퇴·온보딩 미완료 상태 |
| 404 | `FRIENDSHIP_NOT_FOUND` | 관계가 없거나 현재 사용자가 참여자가 아님 |
| 409 | `PROFILE_SETUP_REQUIRED` | 현재 사용자의 온보딩 미완료 |
| 409 | `SELF_FRIEND_REQUEST_NOT_ALLOWED` | 자기 자신에게 신청 |
| 409 | `FRIEND_REQUEST_ALREADY_SENT` | 같은 방향의 요청이 이미 대기 중 |
| 409 | `INCOMING_FRIEND_REQUEST_EXISTS` | 상대가 보낸 요청이 이미 대기 중 |
| 409 | `ALREADY_FRIENDS` | 이미 수락된 친구 |
| 409 | `INVALID_FRIENDSHIP_STATE` | 현재 상태에서 허용되지 않는 전이 |

오류 본문 형식:

```json
{
  "code": "FRIEND_REQUEST_ALREADY_SENT",
  "message": "A friend request has already been sent to this user.",
  "timestamp": "2026-08-24T07:00:00Z"
}
```

## 알림 연결

친구 신청과 수락은 Friendship 트랜잭션 안에서 알림 inbox 및 push outbox에 함께 기록한다. Friendship 변경이 롤백되면 알림도 롤백된다.

| 이벤트 | 수신자 | notification type | idempotency key |
|---|---|---|---|
| 친구 신청 | receiver | `FRIEND_REQUEST` | `friendship:{id}:requested:{requestRevision}` |
| 친구 수락 | requester | `FRIEND_ACCEPTED` | `friendship:{id}:accepted:{requestRevision}` |

FCM data에는 다음 값이 포함된다.

- `friendshipId`
- `actorUserId`
- `requestRevision`
- `screen`: `FRIEND_REQUESTS` 또는 `FRIENDS`

## 공유 기능에서의 사용

프론트가 `GET /api/friendships` 결과로 참여자를 선택하더라도, 공동 영상·기록 생성 API는 저장 직전에 다음을 서버에서 다시 검증해야 한다.

1. 요청자와 모든 참여자가 활성 사용자일 것
2. 요청자와 각 참여자 사이에 `ACCEPTED` Friendship이 존재할 것
3. 중복 참여자와 자기 자신을 제거하거나 거절할 것
4. 관계 검증과 공유 프로젝트 생성을 같은 트랜잭션 경계에서 처리할 것

클라이언트가 보낸 친구 여부를 신뢰하면 관계 삭제 직후에도 공유가 생성될 수 있으므로 서버 검증을 생략하면 안 된다.
