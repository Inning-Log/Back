# Firebase 푸시 프론트 연동 가이드

이 문서는 Flutter 앱이 Inning Log 백엔드의 FCM 등록 API와 맞추기 위한 구현 계약이다. 현재 서버는 **FID를 설치 소유권 키로 사용하고, FCM registration token으로 발송**한다.

## 1. 현재 확정된 방식

```text
deviceId  = FirebaseInstallations.instance.getId() 결과(FID)
pushToken = FirebaseMessaging.instance.getToken() 결과(FCM registration token)
서버 발송 = Firebase Admin SDK addAllTokens()
```

`FirebaseInstallations.instance.getToken()`은 Firebase Installations 인증 토큰이므로 `pushToken` 자리에 보내면 안 된다.

FID는 물리 기기 ID나 사용자 ID가 아니다. 앱 설치 단위 식별자이며 재설치, 앱 데이터 삭제, 백업 복원, 장기 비활성 같은 상황에서 바뀔 수 있다. 따라서 앱은 FID와 FCM 토큰을 로컬 상수처럼 고정하지 말고 현재 값을 다시 동기화해야 한다.

### 왜 지금 `addAllFids()`를 쓰지 않는가

Firebase Admin Java SDK는 FID 대상 발송 API를 제공하지만, 검토 시점(2026-08-24)의 FlutterFire `firebase_messaging 16.5.0` 공개 Dart API는 FCM FID 등록용 `register()/onRegistered` 흐름을 노출하지 않고 `getToken()/onTokenRefresh` 흐름만 제공한다. Android/iOS native FCM에서 FID 발송을 쓰려면 각각 전용 manifest/plist flag와 FCM 등록 callback을 사용해야 한다. `FirebaseInstallations.getId()`로 FIS ID만 읽어서 서버에 보내는 것은 이 FCM 등록 절차가 끝났다는 보장이 아니다.

따라서 지금 `pushToken`을 FID로 이름만 바꾸거나 token 값을 `addAllFids()`에 넘기면 안 된다. FCM은 전환 기간에 token/FID 방식을 함께 지원하므로, Flutter 기본 수신 흐름을 깨는 native bridge를 새로 유지하는 것보다 현재 token 경로가 안전하다. 향후 FlutterFire가 Android/iOS 모두에서 `register/onRegistered/unregister`를 공개하면 client flag 활성화 → dual registration → 실기기 검증 → 서버 `addAllFids()` 전환 → legacy token 제거 순서로 마이그레이션한다.

## 2. Firebase 프로젝트 정보와 아직 필요한 값

- Firebase project ID: `inning-log`
- Messaging sender ID / project number: `782343400535`
- 서버 API: `PUT /api/notifications/push-token`, `DELETE /api/notifications/push-token`

아래 값은 실제 Flutter 프로젝트를 확인한 뒤 Firebase Console에 앱을 등록해야 생성된다.

- Android `applicationId`(package name)
- iOS Bundle ID
- 플랫폼별 Firebase App ID
- 플랫폼별 client API key
- `android/app/google-services.json`
- `ios/Runner/GoogleService-Info.plist`
- `lib/firebase_options.dart`

Android package name과 iOS Bundle ID가 확정되기 전에는 Console에 임의 이름으로 앱을 먼저 만들지 않는다. 실제 식별자와 다르면 설정 파일을 다시 만들어야 한다.

Flutter 프로젝트 루트에서 다음 흐름을 권장한다.

```bash
flutter pub add firebase_core
flutter pub add firebase_messaging
flutter pub add firebase_app_installations
dart pub global activate flutterfire_cli
flutterfire configure --project=inning-log
```

패키지 버전은 Flutter/Dart SDK 제약에 맞춰 `flutter pub add`가 결정하도록 하고 `pubspec.lock`을 커밋한다.

## 3. 플랫폼 설정

### Android

1. 실제 `applicationId`로 Firebase Android 앱을 등록한다.
2. 생성된 `google-services.json`을 `android/app/`에 둔다.
3. FlutterFire 안내대로 Google Services Gradle plugin을 적용한다.
4. Android 13 이상에서 알림 권한을 요청한다.
5. Android 8 이상용 notification channel과 기본 아이콘을 설정한다.
6. FCM FID 직접 등록용 manifest flag는 현재 추가하지 않는다.

### iOS

1. 실제 Bundle ID로 Firebase Apple 앱을 등록한다.
2. `GoogleService-Info.plist`를 Runner target에 포함한다.
3. Xcode에서 `Push Notifications` capability를 켠다.
4. `Background Modes`의 `Background fetch`, `Remote notifications`를 켠다.
5. Apple Developer에서 만든 APNs 인증 키를 Firebase Console의 Cloud Messaging 설정에 업로드한다.
6. FlutterFire의 method swizzling을 유지한다. `FirebaseAppDelegateProxyEnabled=NO`로 끄지 않는다.
7. FCM FID 직접 등록용 plist flag는 현재 추가하지 않는다.

## 4. 앱 초기화와 수신 핸들러

백그라운드 핸들러는 top-level 함수로 두고 entry point를 보존한다.

```dart
@pragma('vm:entry-point')
Future<void> firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  // data를 파싱하고 필요한 로컬 상태만 갱신한다.
}

Future<void> initializeFirebase() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  FirebaseMessaging.onBackgroundMessage(firebaseMessagingBackgroundHandler);
}
```

앱은 다음 네 경로를 모두 처리해야 한다.

- foreground: `FirebaseMessaging.onMessage`
- background 알림 탭: `FirebaseMessaging.onMessageOpenedApp`
- 종료 상태 알림 탭: `FirebaseMessaging.instance.getInitialMessage()`
- background data 처리: `FirebaseMessaging.onBackgroundMessage`

foreground에서 시스템 알림 배너까지 보여주려면 `flutter_local_notifications` 같은 로컬 알림 구현과 Android notification channel이 별도로 필요하다.

## 5. 권한 요청과 등록 동기화

로그인된 사용자의 Inning Log access token이 있을 때만 서버 등록 API를 호출한다.

```dart
Future<bool> syncPushRegistration({String? refreshedToken}) async {
  final settings = await FirebaseMessaging.instance.requestPermission(
    alert: true,
    badge: true,
    sound: true,
  );

  if (settings.authorizationStatus == AuthorizationStatus.denied) {
    final fid = await FirebaseInstallations.instance.getId();
    await notificationApi.disablePushTokenBestEffort(deviceId: fid);
    return true;
  }

  final platform = switch (defaultTargetPlatform) {
    TargetPlatform.android => 'ANDROID',
    TargetPlatform.iOS => 'IOS',
    _ => null,
  };
  if (platform == null) {
    // 이 문서의 현재 구현 범위는 Android/iOS 앱이다.
    return true;
  }

  if (defaultTargetPlatform == TargetPlatform.iOS) {
    final apnsToken = await FirebaseMessaging.instance.getAPNSToken();
    if (apnsToken == null) {
      return false;
    }
  }

  final fid = await FirebaseInstallations.instance.getId();
  final fcmToken = refreshedToken ??
      await FirebaseMessaging.instance.getToken();
  if (fcmToken == null) {
    return false;
  }

  await notificationApi.registerPushToken(
    platform: platform,
    deviceId: fid,
    pushToken: fcmToken,
  );
  return true;
}
```

iOS 첫 실행에서는 APNs token 준비가 늦을 수 있다. listener를 먼저 연결하고, 앱 시작 시 짧은 bounded backoff와 앱 resume 재동기화를 함께 둔다.

```dart
Future<void> syncPushRegistrationWithBoundedRetry() async {
  const delays = [
    Duration.zero,
    Duration(seconds: 1),
    Duration(seconds: 2),
    Duration(seconds: 4),
    Duration(seconds: 8),
  ];

  for (final delay in delays) {
    await Future<void>.delayed(delay);
    try {
      if (await syncPushRegistration()) return;
    } catch (_) {
      // 원문 token/FID를 로그에 남기지 않고 다음 bounded attempt로 진행한다.
    }
  }
}
```

권한 거부는 재시도 대상이 아니며, 기존 서버 등록을 DELETE한 뒤 정상 종료한다. `getAPNSToken()` 또는 `getToken()`이 아직 null인 경우와 일시적 네트워크 실패만 제한된 횟수로 다시 시도한다.

등록 호출 시점은 다음과 같다.

- 로그인 완료 직후
- 앱 시작 시 이미 로그인된 경우
- FCM 토큰 갱신 시
- FID 변경 시
- 알림 권한을 다시 허용한 경우
- 앱이 foreground로 resume된 경우(APNs/FID/token 준비 누락 복구)

```dart
FirebaseMessaging.instance.onTokenRefresh.listen((token) async {
  await syncPushRegistration(refreshedToken: token);
});

FirebaseInstallations.instance.onIdChange.listen((_) async {
  await syncPushRegistration();
});
```

두 스트림이 거의 동시에 호출될 수 있다. 프론트에서도 단일-flight, mutex 또는 짧은 debounce로 중복 API 호출을 직렬화한다. 서버 PUT도 최종 상태 기준으로 멱등 처리된다.

요청 예시는 다음과 같다.

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

## 6. 로그아웃과 같은 설치의 계정 전환

서버는 `deviceId(FID)`를 전역 설치 소유권 키로 관리한다. 같은 설치에서 사용자 B가 등록하면 기존 사용자 A의 행을 B에게 이전한다. A의 늦게 도착한 DELETE는 현재 소유자가 B인지 검사하므로 B의 등록을 끄지 않는다.

권장 순서:

1. 사용자 A의 access token이 살아 있는 동안 DELETE를 best-effort 호출한다.
2. 로컬에서 A의 인증 정보를 제거한다.
3. 사용자 B가 로그인한다.
4. B의 access token으로 현재 FID와 FCM 토큰을 PUT한다.

```http
DELETE /api/notifications/push-token?deviceId={FID}
Authorization: Bearer {USER_A_ACCESS_TOKEN}
```

DELETE 네트워크 실패 때문에 로그아웃 화면을 무한정 막지 않는다. 짧은 timeout을 둔 best-effort 처리로 끝내도 B의 다음 PUT이 최종 소유권을 교정한다.

일반 로그아웃에서 다음 호출은 하지 않는다.

- `FirebaseInstallations.instance.delete()`
- `FirebaseMessaging.instance.deleteToken()`

두 호출은 설치/Firebase 등록 자체를 회전시키므로 단순 계정 로그아웃 정책과 맞지 않는다. 별도의 “이 기기에서 푸시 완전 해제” 기능을 만들 때만 검토한다.

## 7. 수신 payload v1 계약

서버가 모든 알림에 추가하는 공통 `data`:

```json
{
  "type": "FRIEND_REQUEST",
  "notificationId": "123",
  "schemaVersion": "1",
  "occurredAt": "2026-08-24T04:00:00Z",
  "audienceUserId": "42"
}
```

- `notificationId`: DB outbox ID. 서버 발송은 at-least-once이므로 클라이언트 중복 처리 방지 키로 사용한다.
- `schemaVersion`: 모르는 상위 버전이나 모르는 키를 받더라도 앱이 crash하지 않게 파싱한다.
- `type`: 아래 enum 중 하나. 모르는 타입은 일반 알림함으로 fallback하거나 안전하게 무시한다.
- `occurredAt`: 이벤트 발생 시각이며 단말 수신 시각과 다르다.
- `audienceUserId`: 로그인 응답의 `user.id`와 다르면 navigation, in-app 반영, 로컬 알림 표시를 중단한다.

`audienceUserId` 검사는 계정 전환 중 foreground 처리와 딥링크 오동작을 막는 방어선이다. 현재 서버는 FCM notification payload도 사용하므로 background/terminated 상태에서 FCM에 이미 넘겨진 in-flight 메시지는 앱 검사 전에 OS가 잠깐 표시할 수 있다. 이 구간까지 완전 차단하려면 프론트와 **data-only + 검증 후 local notification** 방식으로 전환하거나, 로그아웃 때 FCM token을 회전하는 별도 보안 정책을 합의해야 한다. data-only는 특히 iOS의 background/terminated 전달 제약이 있어 임의로 전환하지 않는다.

도메인 트리거 구현 시 각 알림에는 `deepLink`와 필요한 entity ID를 함께 넣는다. 세부 키는 [서버 알림 설계](notification-backend-spec.md)의 v1 제안을 프론트와 함께 확정한 뒤 구현한다.

알림 탭으로 화면을 열 때는 `notificationId`를 로컬에 짧게 보관하여 동일 알림의 중복 navigation을 막는다.

## 8. 클라이언트 설정과 비밀키 구분

클라이언트 저장소에 포함할 수 있는 값:

- 제한된 Firebase client API key
- Firebase project ID, sender ID, App ID
- Android package name, iOS Bundle ID
- `firebase_options.dart`
- `google-services.json`
- `GoogleService-Info.plist`
- Firebase Console에 등록하는 SHA-1/SHA-256 공개 지문
- Web을 지원할 경우 VAPID public key

Firebase client API key는 서버 비밀키가 아니지만, Firebase 관련 API와 실제 앱으로 API restriction을 설정한다.

프론트에 전달하거나 Git에 저장하면 안 되는 값:

- Firebase Admin 서비스 계정 JSON
- 서비스 계정의 `private_key`, `private_key_id`
- `inning-log-adminsdk.json`
- APNs `.p8` 원본과 Apple private key
- FCM legacy server key
- Google OAuth client secret
- AWS access key/secret

FID와 FCM registration token도 사용자 식별 가능 데이터로 보고 앱 로그, 분석 이벤트, crash report에 원문을 남기지 않는다.

## 9. 프론트 완료 기준

- Android/iOS 실제 app identifier로 Firebase 앱 등록
- FlutterFire 설정 파일 생성 및 각 target 포함 확인
- Android/iOS 권한 및 APNs 설정 완료
- 로그인·앱 재시작·token refresh·FID change 시 PUT 확인
- A 로그아웃 → B 로그인 계정 전환 시 B 소유권 유지 확인
- foreground/background/terminated 수신 확인
- `notificationId` 중복 방지와 알 수 없는 payload 안전 처리
- 딥링크 v1 계약별 화면 이동 테스트
- Admin 서비스 계정 키가 프론트 저장소와 빌드 산출물에 없음을 확인

공식 참고 자료:

- [Flutter에서 FCM 설정](https://firebase.google.com/docs/cloud-messaging/flutter/get-started)
- [FCM registration token 관리](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [Flutter FirebaseMessaging API](https://pub.dev/documentation/firebase_messaging/latest/firebase_messaging/FirebaseMessaging-class.html)
- [Flutter FirebaseInstallations API](https://pub.dev/documentation/firebase_app_installations/latest/firebase_app_installations/FirebaseInstallations-class.html)
- [FCM 등록 token에서 FID로 전환 중인 공식 관리 지침](https://firebase.google.com/docs/cloud-messaging/manage-tokens)
- [Android FCM FID 등록 절차](https://firebase.google.com/docs/cloud-messaging/android/get-started)
- [Apple FCM FID 등록 절차](https://firebase.google.com/docs/cloud-messaging/ios/get-started)
- [Firebase Admin Java 9.10.0 FID 지원 및 token API deprecation](https://firebase.google.com/support/release-notes/admin/java)
- [Firebase API key 사용과 제한](https://firebase.google.com/docs/projects/api-keys)
