# 웹 FCM 연동 필수사항

## 1. 필요한 값

```env
FIREBASE_API_KEY=
FIREBASE_AUTH_DOMAIN=
FIREBASE_PROJECT_ID=inning-log
FIREBASE_MESSAGING_SENDER_ID=782343400535
FIREBASE_APP_ID=
FIREBASE_VAPID_PUBLIC_KEY=
```

Firebase Web config와 VAPID public key는 프론트 공개 환경변수로 사용한다.

## 2. Firebase 초기화

```bash
npm install firebase
```

- Firebase 앱과 Messaging을 초기화한다.
- 동일 origin의 `/firebase-messaging-sw.js`를 등록한다.
- 운영 환경은 HTTPS를 사용한다.

## 3. FID 등록

`onRegistered`를 먼저 구독한 후 `register`를 호출한다.

```ts
import { getMessaging, onRegistered, register } from "firebase/messaging";

const messaging = getMessaging(firebaseApp);
const serviceWorkerRegistration =
  await navigator.serviceWorker.register("/firebase-messaging-sw.js");

onRegistered(messaging, async (installationId) => {
  await fetch("/api/notifications/push-registration", {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ platform: "WEB", installationId }),
  });
});

await register(messaging, {
  vapidKey: FIREBASE_VAPID_PUBLIC_KEY,
  serviceWorkerRegistration,
});
```

로그인 완료 후와 로그인 상태로 앱을 시작할 때 실행한다.

## 4. 로그아웃

access token을 삭제하기 전에 호출한다. 실패해도 로그아웃은 계속 진행한다.

```http
DELETE /api/notifications/push-registration?installationId={FID}
Authorization: Bearer {ACCESS_TOKEN}
```

일반 로그아웃에서는 Firebase `unregister()`를 호출하지 않는다. 다른 계정으로 로그인하면 같은 FID를 새 계정의 access token으로 다시 PUT한다.

## 5. 알림 처리

- foreground 알림은 `onMessage`에서 처리한다.
- background 알림은 service worker가 수신할 수 있어야 한다.
- `audienceUserId`가 현재 사용자 ID와 다르면 무시한다.
- `notificationId`로 중복 처리를 막는다.
- `link`는 알림 클릭 시 이동할 HTTPS URL이다.

## 6. 금지사항

- Firebase Admin 서비스 계정 JSON/private key를 프론트에 저장하지 않는다.
- FID와 알림 payload 원문을 로그나 분석 도구에 남기지 않는다.
