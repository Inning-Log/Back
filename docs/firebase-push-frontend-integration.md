# Web FCM 프론트 연동 필수사항

## Firebase 설정

1. Firebase Console `inning-log` 프로젝트에서 Web 앱을 등록하고 Web config를 복사한다.
2. `프로젝트 설정 > Cloud Messaging > Web Push 인증서`에서 VAPID key pair를 생성한다.
3. Firebase JS SDK를 설치한다.

```bash
npm install firebase
```

프론트의 public 환경변수 규칙에 맞춰 다음 값을 설정한다.

```env
FIREBASE_API_KEY=
FIREBASE_AUTH_DOMAIN=
FIREBASE_PROJECT_ID=inning-log
FIREBASE_MESSAGING_SENDER_ID=782343400535
FIREBASE_APP_ID=
FIREBASE_VAPID_PUBLIC_KEY=
```

Web config와 VAPID public key는 클라이언트 공개 값이다. Firebase Admin 서비스 계정 JSON/private key는 프론트에 전달하거나 저장하지 않는다.

## Service Worker

동일 origin의 `/firebase-messaging-sw.js`를 빌드 결과에 포함하고 Firebase Messaging을 초기화한다.

```ts
import { initializeApp } from "firebase/app";
import { getMessaging } from "firebase/messaging/sw";

const app = initializeApp(firebaseConfig);
getMessaging(app);
```

운영은 HTTPS가 필수이며 `localhost`만 예외다. 기존 PWA service worker가 있으면 별도로 만들지 말고 그 worker를 `serviceWorkerRegistration`으로 넘긴다.

## FID 등록

`onRegistered`를 먼저 구독한 뒤 `register`를 호출한다.

```ts
import {
  getMessaging,
  isSupported,
  onMessage,
  onRegistered,
  onUnregistered,
  register,
} from "firebase/messaging";

if (await isSupported()) {
  const messaging = getMessaging(firebaseApp);
  const serviceWorkerRegistration =
    await navigator.serviceWorker.register("/firebase-messaging-sw.js");

  onRegistered(messaging, async (installationId) => {
    const accessToken = getAccessToken();
    if (!accessToken) return;

    await fetch("/api/notifications/push-registration", {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ platform: "WEB", installationId }),
    });
  });

  onUnregistered(messaging, async (installationId) => {
    await disableRegistrationBestEffort(installationId);
  });

  await register(messaging, {
    vapidKey: FIREBASE_VAPID_PUBLIC_KEY,
    serviceWorkerRegistration,
  });

  onMessage(messaging, handleForegroundMessage);
}
```

동기화 시점:

- 로그인 완료 후
- 로그인 상태로 앱 시작 시
- `onRegistered`가 다시 호출될 때

Android에 설치한 PWA도 `platform: "WEB"`이다. 향후 네이티브 Android 앱만 `platform: "ANDROID"`를 사용한다.

## 로그아웃과 계정 전환

로그아웃 시 access token을 지우기 전에 best-effort로 호출한다.

```http
DELETE /api/notifications/push-registration?installationId={FID}
Authorization: Bearer {ACCESS_TOKEN}
```

일반 로그아웃에서는 Firebase `unregister()`를 호출하지 않는다. 새 계정 로그인 후 같은 FID를 PUT하면 서버가 설치 소유자를 새 사용자로 변경한다.

## 수신 처리

서버 payload의 `data` 형식:

```json
{
  "type": "FRIEND_REQUEST",
  "notificationId": "123",
  "schemaVersion": "1",
  "occurredAt": "2026-08-24T04:00:00Z",
  "audienceUserId": "42",
  "requestId": "10",
  "actorUserId": "7",
  "link": "https://{FRONTEND_HOST}/friends/requests/10"
}
```

- foreground는 `onMessage`에서 앱 내 알림을 표시한다.
- background/종료 상태는 브라우저가 알림을 표시한다.
- 서버는 `link`를 Web FCM 클릭 URL로 설정하므로 반드시 운영 HTTPS URL을 보낸다.
- `audienceUserId`가 현재 사용자와 다르면 처리하지 않는다.
- `notificationId`로 중복 화면 이동을 막는다.
- 알 수 없는 `schemaVersion`/`type`은 무시한다.
- FID와 payload 원문을 로그·분석·crash report에 남기지 않는다.

## 확인 항목

- Chrome 데스크톱과 Android Chrome/PWA에서 권한 허용 및 FID PUT 성공
- foreground, background, 브라우저 종료 상태 수신
- 알림 클릭 시 `link` 이동
- 로그아웃 후 이전 사용자 등록 비활성화
- 같은 설치에서 다른 계정 로그인 시 새 사용자로 소유권 이전
