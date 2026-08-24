# Firebase 푸시 프론트 연동 필수사항

## 1. 서버 계약

- Firebase project ID: `inning-log`
- Messaging sender ID: `782343400535`
- `deviceId`: `FirebaseInstallations.instance.getId()`로 얻은 FID
- `pushToken`: `FirebaseMessaging.instance.getToken()`으로 얻은 FCM registration token

`FirebaseInstallations.instance.getToken()`은 Installations 인증 토큰이므로 `pushToken`으로 보내면 안 된다.

### 등록·갱신

```http
PUT /api/notifications/push-token
Authorization: Bearer {INNING_LOG_ACCESS_TOKEN}
Content-Type: application/json

{
  "platform": "ANDROID",
  "deviceId": "{FID}",
  "pushToken": "{FCM_REGISTRATION_TOKEN}"
}
```

`platform`은 `ANDROID` 또는 `IOS`다.

### 비활성화

```http
DELETE /api/notifications/push-token?deviceId={FID}
Authorization: Bearer {INNING_LOG_ACCESS_TOKEN}
```

## 2. Firebase 설정

필요 패키지:

```bash
flutter pub add firebase_core
flutter pub add firebase_messaging
flutter pub add firebase_app_installations
dart pub global activate flutterfire_cli
flutterfire configure --project=inning-log
```

Firebase Console 앱 등록에 실제 값이 필요하다.

- Android `applicationId`
- iOS Bundle ID

생성 파일:

- `android/app/google-services.json`
- `ios/Runner/GoogleService-Info.plist`
- `lib/firebase_options.dart`

Android:

- Google Services Gradle plugin 적용
- Android 13 이상 알림 권한 요청
- Android 8 이상 notification channel과 기본 아이콘 설정

iOS:

- Xcode `Push Notifications` capability 활성화
- `Background Modes`에서 `Background fetch`, `Remote notifications` 활성화
- APNs 인증 키를 Firebase Console Cloud Messaging 설정에 업로드
- `FirebaseAppDelegateProxyEnabled=NO`를 설정하지 않음

## 3. 등록 호출 시점

로그인된 사용자의 access token이 있을 때 다음 시점에 FID와 FCM token을 다시 읽어 PUT한다.

- 로그인 완료 직후
- 로그인 상태로 앱 시작
- 앱 foreground 복귀
- `FirebaseMessaging.instance.onTokenRefresh`
- `FirebaseInstallations.instance.onIdChange`
- 알림 권한을 다시 허용한 경우

iOS는 APNs token이 준비된 뒤 FCM token을 요청한다. APNs/FCM token이 아직 `null`이면 짧은 제한 재시도 후 앱 foreground 복귀 때 다시 동기화한다.

`onTokenRefresh`와 `onIdChange`가 동시에 발생할 수 있으므로 프론트에서 단일-flight, mutex 또는 debounce로 PUT을 직렬화한다.

알림 권한이 거부되면 현재 FID로 DELETE를 best-effort 호출한다.

## 4. 로그아웃과 계정 전환

1. 이전 사용자의 access token이 유효할 때 DELETE를 best-effort 호출한다.
2. 이전 사용자 인증 정보를 삭제한다.
3. 새 사용자가 로그인하면 현재 FID와 FCM token을 PUT한다.

일반 로그아웃에서는 아래 호출을 하지 않는다.

- `FirebaseInstallations.instance.delete()`
- `FirebaseMessaging.instance.deleteToken()`

DELETE 실패 때문에 로그아웃을 계속 막지 않는다. 새 사용자의 PUT이 같은 설치의 소유권을 새 계정으로 갱신한다.

## 5. 수신 처리

다음 경로를 모두 구현한다.

- foreground: `FirebaseMessaging.onMessage`
- background 알림 탭: `FirebaseMessaging.onMessageOpenedApp`
- 종료 상태 알림 탭: `FirebaseMessaging.instance.getInitialMessage()`
- background data 처리: `FirebaseMessaging.onBackgroundMessage`

백그라운드 핸들러는 top-level 함수와 `@pragma('vm:entry-point')`를 사용한다.

foreground에서 배너를 보여주려면 `flutter_local_notifications` 등으로 로컬 알림을 구현한다.

## 6. 공통 payload v1

```json
{
  "type": "FRIEND_REQUEST",
  "notificationId": "123",
  "schemaVersion": "1",
  "occurredAt": "2026-08-24T04:00:00Z",
  "audienceUserId": "42",
  "deepLink": "inninglog://friends/42"
}
```

처리 규칙:

- `audienceUserId`가 현재 로그인 사용자 ID와 다르면 화면 이동과 앱 내부 처리를 중단한다.
- `notificationId`로 중복 화면 이동과 중복 로컬 알림을 방지한다.
- 모르는 `schemaVersion`, `type`, 추가 key가 와도 앱이 crash하지 않게 처리한다.
- token, FID, payload 원문을 앱 로그·분석 이벤트·crash report에 남기지 않는다.

알림 타입:

| type | 추가 data |
|---|---|
| `FRIEND_REQUEST` | `requestId`, `actorUserId`, `deepLink` |
| `FRIEND_ACCEPTED` | `friendshipId`, `actorUserId`, `deepLink` |
| `TIMELINE_REACTION` | `timelineId`, `actorUserId`, `reactionType`, `deepLink` |
| `TIMELINE_COMMENT` | `timelineId`, `commentId`, `actorUserId`, `deepLink` |
| `GAME_INNING_STARTED` | `gameId`, `inning`, `half`, `deepLink` |
| `GAME_INNING_ENDED` | `gameId`, `inning`, `half`, `deepLink` |
| `GAME_SCORE_CHANGED` | `gameId`, `homeScore`, `awayScore`, `deepLink` |
| `RECORD_REMINDER` | `gameId`, `inning`, `deepLink` |
| `GENERATED_VIDEO_READY` | `videoId`, `deepLink` |
| `GENERATED_VIDEO_FAILED` | `videoId`, `deepLink` |

## 7. 저장소 포함 여부

프론트 저장소에 포함 가능:

- `firebase_options.dart`
- `google-services.json`
- `GoogleService-Info.plist`
- Firebase client API key, project ID, sender ID, App ID

프론트에 전달하거나 Git에 저장하면 안 됨:

- Firebase Admin 서비스 계정 JSON과 private key
- APNs `.p8` 원본
- FCM legacy server key
- Google OAuth client secret
- AWS access key/secret

## 8. 완료 체크리스트

- Android/iOS 앱을 실제 identifier로 Firebase Console에 등록
- 설정 파일과 APNs 설정 완료
- 로그인·앱 시작·foreground 복귀·token/FID 변경 시 PUT 확인
- 로그아웃 시 DELETE 후 다른 계정 PUT 확인
- foreground/background/terminated 수신 확인
- `audienceUserId` 검사와 `notificationId` 중복 방지 확인
- 타입별 deep link 처리 확인
- Admin credential이 프론트 저장소와 빌드 산출물에 없는지 확인
