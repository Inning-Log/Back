# Inning Log 서버 알림 설계 및 운영 기준

## 1. Figma 기준 알림 카탈로그

Figma의 `홈 - 알림 화면`, `홈 - 알림 설정`을 기준으로 서버 알림 타입을 다음과 같이 분리한다. 기존의 포괄적인 `GAME_PROGRESS` 하나보다 타입별 트리거·딥링크·통계를 명확히 할 수 있다.

| 설정 영역 | 서버 타입 | 사용자 예시 | v1 data 제안 | 상태 |
|---|---|---|---|---|
| 친구 신청 | `FRIEND_REQUEST` | `@inning님이 친구 신청을 보냈습니다.` | `requestId`, `actorUserId`, `link` | 도메인 구현 필요 |
| 친구 신청 | `FRIEND_ACCEPTED` | `@inning님이 친구신청을 수락했습니다.` | `friendshipId`, `actorUserId`, `link` | 도메인 구현 필요 |
| 댓글 또는 반응 | `TIMELINE_REACTION` | `@inning님이 회원님의 기록에 반응했습니다.` | `timelineId`, `actorUserId`, `reactionType`, `link` | MVP 제안, 도메인 구현 필요 |
| 댓글 또는 반응 | `TIMELINE_COMMENT` | `@inning님이 댓글을 달았습니다.` | `timelineId`, `commentId`, `actorUserId`, `link` | Figma 메모상 댓글은 후순위 |
| 경기 진행 상황 | `GAME_INNING_STARTED` | `1회가 시작되었습니다!` | `gameId`, `inning`, `half`, `link` | 경기 feed/도메인 필요 |
| 경기 진행 상황 | `GAME_INNING_ENDED` | `1회가 종료되었습니다.` | `gameId`, `inning`, `half`, `link` | 경기 feed/도메인 필요 |
| 경기 진행 상황 | `GAME_SCORE_CHANGED` | `응원 팀의 점수가 변경되었습니다.` | `gameId`, `homeScore`, `awayScore`, `link` | 경기 feed/도메인 필요 |
| 기록 독촉 | `RECORD_REMINDER` | `9회가 끝나기 전에 기록해주세요.` | `gameId`, `inning`, `link` | 기록/스케줄러 도메인 필요 |
| 비동기 결과 | `GENERATED_VIDEO_READY` | 생성 영상 준비 완료 | `videoId`, `link` | 영상 작업 도메인 필요 |
| 비동기 결과 | `GENERATED_VIDEO_FAILED` | 생성 영상 처리 실패 | `videoId`, `link` | 영상 작업 도메인 필요 |

Figma의 `신청 대기` 탭은 친구 신청 원본 상태를 조회해야 한다. 푸시 outbox를 친구 신청 목록의 원본 데이터로 사용하면 안 된다.

알림 설정 UI와 서버 preference는 다음 묶음이 자연스럽다.

- 경기 진행 상황: `GAME_INNING_STARTED`, `GAME_INNING_ENDED`, `GAME_SCORE_CHANGED`
- 기록 독촉: `RECORD_REMINDER`
- 댓글 또는 반응: `TIMELINE_REACTION`, 향후 `TIMELINE_COMMENT`

친구 신청/수락과 영상 작업 결과는 현재 필수 알림으로 취급해 항상 푸시한다. 별도 토글을 추가하려면 제품 정책과 API 계약을 함께 변경한다.

## 2. 현재 작업 브랜치에서 코드·자동 테스트가 완료된 서버 범위

- 인증 사용자별 FCM 앱 설치 등록/비활성화 API
- 앱 내 알림 inbox 저장, 최신순 cursor pagination, 단건/전체 읽음 처리 API
- 사용자별 경기 진행·기록 독촉·소셜 반응 푸시 설정 조회/부분 수정 API
- 동일 도메인 이벤트의 inbox/outbox 원자적 멱등 생성
- 설정을 꺼도 inbox는 생성하고 선택 푸시 outbox만 생략
- enqueue 뒤 설정이 바뀐 경우 worker가 FCM 호출 직전에 다시 검사해 미발송 처리
- `platform + installationId(FID)` 계약과 `addAllFids()` 발송
- 웹/PWA `WEB`, 향후 네이티브 Android `ANDROID`, iOS `IOS` 플랫폼 확장성 유지
- 동일 설치에서 새 사용자가 로그인하면 설치 소유권 이전
- 이전 사용자의 늦은 DELETE가 새 소유자의 등록을 비활성화하지 않는 소유권 검사
- DB 잠금과 전역 `installation_id` unique를 통한 동시 등록 안정화
- 적용 전 중복 FID를 최신 `last_seen_at` 등록 우선으로 정리하고 legacy token 컬럼을 제거하는 Flyway V11/V12
- 핵심 비즈니스 트랜잭션과 함께 outbox INSERT
- FCM 네트워크 호출을 비즈니스 트랜잭션 및 DB 잠금 밖에서 실행
- 대상별 `PENDING → PROCESSING → SENT/RETRY/INVALID/DEAD` 상태
- worker crash 복구용 processing lease와 at-least-once 발송
- 지수 backoff + jitter, 최대 시도 횟수, quota 오류 최소 60초 backoff
- 성공한 대상은 재발송하지 않고 실패한 대상만 재시도
- FID 재동기화 전에 시작된 과거 invalid 응답이 최신 등록을 끄지 않는 조건부 정리
- 4,096 UTF-8 byte payload 제한과 FCM 예약 data key 검사
- FID/payload 원문을 제외한 구조화 로그와 bounded-tag 메트릭
- 계정 탈퇴 시 사용자의 push 등록 일괄 비활성화

이 목록은 코드 상태를 뜻한다. Firebase Web 앱 등록과 VAPID public key 설정, AWS 실제 배포, 브라우저/PWA 종단 FCM smoke test는 아직 완료되지 않았다. 네이티브 Android/iOS 앱을 추가할 때는 해당 Firebase 앱 등록과 플랫폼별 설정이 별도로 필요하다.

## 3. 아직 구현되지 않은 제품 기능

알림 inbox·설정·FCM 큐는 완성됐지만 각 제품 도메인은 상태 변경 트랜잭션에서 `NotificationQueueService.enqueueToUser()`를 호출해야 한다. 호출하지 않은 도메인의 알림은 자동 생성되지 않는다.

완전한 Figma 알림 시스템을 위해 서버에 추가로 필요한 작업:

1. 아직 연결되지 않은 타임라인 reaction 이벤트
2. 경기 feed 수집 또는 경기 상태 변경 이벤트
3. 기록 여부를 판단하는 reminder scheduler
4. 친구 `신청 대기` 목록 API
5. 생성 영상 job 완료/실패 이벤트
6. 경기 알림 만료 시간과 FCM TTL 정책
7. 완료 outbox/target 보존기간 및 정리 job

사용자별 등록 기기 수 제한과 등록 API rate limit은 요청에 따라 이번 범위에서는 보류한다. 인증 사용자가 많은 임의 FID를 등록할 수 있는 잔여 위험은 운영 전 별도 보안 항목으로 추적한다.

등록 PUT/DELETE의 교차 충돌을 확실히 막기 위해 현재는 단일 DB registration lock으로 직렬화한다. 같은 FID 경합과 계정 전환의 정확성은 보장하지만 서로 무관한 설치의 등록도 잠깐 대기하므로, 등록량이 커지거나 서버를 수평 확장하기 전에는 PostgreSQL key-scoped advisory lock 또는 동등한 원자 reconciliation으로 교체하고 부하 테스트해야 한다.

## 4. 비즈니스 코드에서 enqueue하는 방법

알림은 실제 도메인 상태 변경과 같은 트랜잭션 안에서 enqueue한다. `NotificationQueueService`는 `MANDATORY`이므로 트랜잭션 없이 잘못 호출하면 즉시 실패한다. 호출 시 앱 inbox를 먼저 만들고, 해당 타입의 푸시 설정이 켜져 있을 때만 같은 트랜잭션에 outbox를 만든다.

```java
@Transactional
public void acceptFriendRequest(Long requestId, Long acceptingUserId) {
    FriendRequest request = friendRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(FriendRequestNotFoundException::new);
    request.accept(acceptingUserId);

    notificationQueueService.enqueueToUser(
            "friend-request:" + requestId + ":accepted",
            request.getRequesterId(),
            new PushNotification(
                    NotificationType.FRIEND_ACCEPTED,
                    "친구 신청이 수락됐어요",
                    request.getAcceptorDisplayName() + "님이 친구신청을 수락했습니다.",
                    Map.of(
                            "friendshipId", request.getFriendshipId().toString(),
                            "actorUserId", acceptingUserId.toString(),
                            "link", frontendBaseUrl + "/friends/" + acceptingUserId)));
}
```

idempotency key는 동일 도메인 이벤트에서 항상 같은 값이 되게 구성한다. 예:

- `friendship:{friendshipId}:requested:{requestRevision}`
- `friendship:{friendshipId}:accepted:{requestRevision}`
- `game:{gameId}:inning:{inning}:{half}:started`
- `video:{videoId}:ready`

멱등 범위는 `(userId, idempotency key)`다. 같은 이벤트를 여러 사용자에게 보낼 때는 사용자별로 한 행씩 생성되고, 같은 사용자에게 동시에 같은 key가 enqueue돼도 PostgreSQL 원자 INSERT와 composite unique constraint로 한 행만 생성한다.
Friendship은 거절된 행을 재신청에 재사용하므로 `requestRevision`을 key에 반드시 포함해 새 요청 주기의 알림이 이전 주기와 중복 제거되지 않게 한다.

### 트랜잭션 선택의 의미

- 도메인 트랜잭션이 rollback되면 outbox도 rollback된다. 존재하지 않는 이벤트의 알림이 나가지 않는다.
- 도메인 commit 후 FCM 장애가 나도 도메인 결과는 rollback되지 않는다. worker가 별도 트랜잭션에서 재시도한다.
- outbox INSERT 자체의 DB 장애나 잘못된 payload는 도메인 트랜잭션을 실패시킬 수 있다. 이는 이벤트 유실 없이 원자성을 지키기 위한 transactional outbox의 의도된 성질이다.
- FCM 네트워크, Firebase 인증, 개별 FID 오류는 핵심 비즈니스 트랜잭션에 전파되지 않는다.

## 5. 발송 보장과 계정 전환 경계

worker는 짧은 트랜잭션에서 대상을 `PROCESSING`으로 claim하고 lease를 저장한 뒤 commit한다. FCM은 트랜잭션 밖에서 호출하고 결과는 새 트랜잭션으로 반영한다.

프로세스가 FCM 성공 후 결과 저장 전에 죽으면 lease 만료 뒤 같은 inbox `notificationId`가 다시 발송될 수 있다. 정확히 한 번 발송은 FCM에서 보장할 수 없으므로 클라이언트가 `notificationId`로 중복 화면 이동과 중복 로컬 처리를 막아야 한다.

발송 직전 등록 행이 비활성화됐거나 outbox 사용자와 소유자가 달라지면 `CANCELLED_OWNERSHIP`으로 보내지 않는다. 선택 알림 설정이 꺼졌으면 대상 발송도 취소한다. 다만 이미 FCM에 전달 중인 메시지를 서버가 회수할 수는 없으므로 계정 전환이나 설정 변경과 동시에 진행 중인 극히 짧은 in-flight 구간까지 완전 취소한다고 보장하지 않는다.

## 6. payload 제한

공통 data:

- `type`
- `notificationId`
- `schemaVersion=1`
- `occurredAt`
- `audienceUserId`

도메인 발송자는 여기에 entity ID와 절대 HTTPS `link`를 추가한다. `link`는 Web FCM의 알림 클릭 URL로도 설정되며 Android App Links/iOS Universal Links로 확장할 수 있다. 모든 값은 문자열이어야 한다.

서버는 title, body, data를 합친 JSON의 UTF-8 크기가 4,096 byte를 초과하면 enqueue 전에 거절한다. 다음 key는 사용할 수 없다.

- `from`
- `message_type`
- `google.` prefix
- `gcm.` prefix

token, FID, 서비스 계정 값, 사용자 payload 원문은 로그와 metric tag로 남기지 않는다.

## 7. 재시도와 관측성

기본 설정:

```env
FIREBASE_DISPATCH_BATCH_SIZE=100
FIREBASE_DISPATCH_MAX_PER_TICK=20
FIREBASE_DISPATCH_MAX_ATTEMPTS=5
FIREBASE_DISPATCH_INITIAL_BACKOFF=10s
FIREBASE_DISPATCH_MAX_BACKOFF=1h
FIREBASE_DISPATCH_CLAIM_LEASE=5m
FIREBASE_DISPATCH_FIXED_DELAY=5s
FIREBASE_DISPATCH_INITIAL_DELAY=10s
```

재시도 대상에는 `UNAVAILABLE`, `INTERNAL`, `QUOTA_EXCEEDED`, platform `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, 알 수 없는 전체 batch 실패가 포함된다. FID 자동 비활성화는 `UNREGISTERED` 또는 FID 발송의 platform `NOT_FOUND`에만 적용한다. `INVALID_ARGUMENT`, `SENDER_ID_MISMATCH`는 terminal 실패로 관측하고 설정을 점검한다.

주요 metric:

- `inninglog.push.queue.depth{queue,status}`
- `inninglog.push.outbox.total{type,outcome}`
- `inninglog.push.target.total{type,outcome}`
- `inninglog.push.failure.total{type,outcome,error_code}`
- `inninglog.push.delivery.duration{type}`

`error_code`는 허용 목록으로 정규화해 cardinality를 제한한다. 실패 batch 로그에는 token/FID/payload 없이 `outboxId`, type, `outcome:errorCode -> count`만 남긴다. 영구 실패로 끝난 outbox도 WARN으로 남긴다. Actuator는 기본 9090/loopback management port에 둔다. EC2 Docker 배포에서는 컨테이너 내부 management address만 `0.0.0.0`으로 열고 workflow가 host `127.0.0.1:9090`에만 publish한다.

## 8. AWS EC2 배포

FCM을 위해 Cloud Run이나 별도 GCP compute 배포는 필요하지 않다. AWS의 Spring 서버가 Firebase Admin SDK로 FCM API를 호출한다.

EC2 호스트에 서비스 계정 JSON을 저장한다. Git 저장소나 Docker image에 COPY하지 않는다.

### 서비스 계정 IAM과 키 수명

운영 발송용으로 단일 목적 서비스 계정을 별도로 만들고 대상 Firebase 프로젝트 `inning-log`에서 `Firebase Cloud Messaging API Admin`(`roles/firebasecloudmessaging.admin`)만 부여한다. 로그인/OAuth나 다른 Firebase 관리 작업에 쓰는 계정과 공유하지 않는다. Firebase Console에서 자동 생성된 광범위 `firebase-adminsdk` 계정 키는 초기 연결 확인에는 동작하지만, 운영 배포 전 전용 발송 계정으로 교체하는 것이 원칙이다.

AWS에서 장기 JSON key를 없애려면 추후 Workload Identity Federation을 적용할 수 있다. 당장은 JSON을 쓰되 다음 수명 주기를 지킨다.

1. 새 key를 생성해 EC2의 별도 임시 경로에 저장한다.
2. credential mount를 새 파일로 바꾸고 FirebaseApp 초기화 및 실기기 발송을 확인한다.
3. 기존 key를 먼저 disable하고 오류가 없는지 관찰한다.
4. 더 이상 사용되지 않음을 확인한 뒤 기존 key를 delete한다.
5. 노출이 의심되면 관찰 기간 없이 즉시 새 key로 교체하고 기존 key를 disable한다.

다운로드 폴더, 메신저, 이슈 본문, CI 로그, Docker layer에 key 사본을 남기지 않는다. 운영 key는 정기 회전 일정을 두고 key authentication metric과 IAM audit log로 미사용·이상 사용을 점검한다.

```bash
sudo install -d -m 750 -o root -g 10001 /etc/inninglog
sudo install -m 640 -o root -g 10001 ./inning-log-adminsdk.json \
  /etc/inninglog/firebase-admin.json
```

컨테이너의 고정 appuser group ID는 `10001`이다. 배포 workflow는 Firebase가 켜진 경우 이 파일을 `/run/secrets/firebase-admin.json`에 read-only bind mount한다.

EC2 env file 예시:

```env
FIREBASE_ENABLED=true
FIREBASE_PROJECT_ID=inning-log
GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-admin.json
MANAGEMENT_SERVER_ADDRESS=0.0.0.0
MANAGEMENT_SERVER_PORT=9090
```

GitHub Actions secret `EC2_FIREBASE_CREDENTIALS_FILE`을 생략하면 호스트 경로 `/etc/inninglog/firebase-admin.json`을 사용한다. 다른 경로를 쓸 때만 secret을 설정한다. 서비스 계정 JSON 내용 자체를 GitHub Actions secret이나 env file 한 줄에 넣지 않는다.

운영 확인:

1. 배포 전 host credential 파일 존재 및 권한 확인
2. 애플리케이션 시작 시 FirebaseApp 초기화 성공 확인
3. `/api/health` 확인
4. EC2 host에서 `curl http://127.0.0.1:9090/actuator/metrics`로 queue/dead metric 확인
5. 실제 브라우저/PWA의 FID 등록 후 smoke test
6. 로그에 token/FID/payload/키 원문이 없는지 확인

management port를 `0.0.0.0` host address로 publish하거나 AWS Security Group에 9090을 열지 않는다. workflow의 management publish 주소는 의도적으로 `127.0.0.1`에 고정돼 있다.

공식 참고:

- [Firebase Admin SDK로 FCM 발송 및 필요한 IAM 역할](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk)
- [Google Cloud 서비스 계정 key 관리 모범 사례](https://cloud.google.com/iam/docs/best-practices-for-managing-service-account-keys)
- [서비스 계정 key 회전](https://cloud.google.com/iam/docs/key-rotation)
