# Google Login Integration Guide

이 문서는 현재 백엔드 구현을 기준으로 Google 로그인 연결에 필요한 외부 설정과 프론트 작업을 정리한다.

현재 방식은 Spring Security `oauth2Login()` redirect 흐름이 아니다.
프론트가 Google Identity Services SDK로 Google ID Token을 받고, 백엔드가 그 토큰을 검증한 뒤 Inning Log JWT를 발급한다.

```text
Frontend
  Google Identity Services SDK
  Google ID Token credential 획득
        |
        v
Backend
  POST /api/auth/google
  Google ID Token 검증
  User / OAuthAccount 조회 또는 생성
  Inning Log JWT 발급
```

## Backend Status

백엔드 구현은 완료된 상태로 본다.

구현된 API:

```http
POST /api/auth/google
GET  /api/auth/me
```

Google 로그인 요청:

```http
POST /api/auth/google
Content-Type: application/json

{
  "credential": "GOOGLE_ID_TOKEN"
}
```

성공 응답 예:

```json
{
  "tokenType": "Bearer",
  "accessToken": "INNING_LOG_JWT",
  "expiresAt": "2026-07-10T00:00:00Z",
  "isNewUser": true,
  "user": {
    "id": 1,
    "email": "user@gmail.com",
    "nickname": "User Name",
    "profileImageUrl": "https://...",
    "onboardingCompleted": false
  }
}
```

백엔드는 Google ID Token에서 아래를 검증한다.

- Google 공개키 기반 서명
- `aud`가 `GOOGLE_CLIENT_ID`와 일치하는지
- `iss`가 `accounts.google.com` 또는 `https://accounts.google.com`인지
- `exp`가 만료되지 않았는지
- `email_verified`가 `true`인지

Google 계정 식별자는 이메일이 아니라 `sub`를 사용한다.

## Google Cloud Setup

### 1. Google Cloud Project

Google Cloud Console에서 Inning Log용 프로젝트를 만든다.

권장:

```text
Project name: Inning Log
```

개인 계정보다는 팀에서 계속 관리 가능한 Google 계정 또는 조직 계정으로 만드는 것이 좋다.

### 2. OAuth Branding / Consent Screen

Google Auth Platform의 Branding 또는 OAuth consent screen 영역에서 앱 정보를 설정한다.

개발 단계 권장값:

```text
App name: Inning Log
User support email: 팀 또는 담당자 이메일
Developer contact information: 팀 또는 담당자 이메일
Publishing status: Testing
User type: External
```

운영 전에는 다음 항목도 준비한다.

```text
Homepage URL
Privacy Policy URL
Terms of Service URL
Authorized domain
```

로그인만 필요하면 scope는 기본 로그인용으로 충분하다.

```text
openid
profile
email
```

Google Drive, Calendar 같은 API 권한은 현재 필요 없다.

### 3. OAuth Client 생성

Google Cloud Console의 Clients/Credentials 영역에서 OAuth client를 만든다.

```text
Application type: Web application
Name: Inning Log Web Client
```

생성 후 나오는 Client ID를 백엔드와 프론트에 모두 설정한다.

```text
GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
```

현재 방식에서는 백엔드가 Google authorization code를 교환하지 않으므로 `GOOGLE_CLIENT_SECRET`은 사용하지 않는다.

### 4. Authorized JavaScript origins

프론트에서 Google Identity Services SDK를 사용하므로 JavaScript origin 등록이 필요하다.

로컬 개발 예:

```text
http://localhost:5173
http://localhost:3000
```

운영 예:

```text
https://inninglog.com
https://www.inninglog.com
```

Origin은 scheme, host, port까지만 포함한다. path는 넣지 않는다.

### 5. Authorized redirect URIs

현재 구현은 popup/callback 방식으로 credential을 프론트에서 받아 백엔드 API로 전송하는 구조다.
따라서 백엔드 OAuth redirect URI인 `/login/oauth2/code/google`은 사용하지 않는다.

프론트가 HTML `data-login_uri` 또는 redirect UX mode를 쓰는 경우에만 redirect URI가 필요하다.
현재 추천은 JavaScript callback 방식이다.

## Backend Environment Variables

백엔드 실행 환경에는 최소 아래 값이 필요하다.

```env
GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
JWT_SECRET=replace-with-a-strong-secret-at-least-32-bytes
```

선택 설정:

```env
JWT_ISSUER=inning-log
JWT_ACCESS_TOKEN_EXPIRATION=1h
APP_AUTH_DEV_TOKEN_ENABLED=false
```

로컬 개발에서는 `application-local.yml` 또는 IDE run configuration에 설정한다.
운영에서는 GitHub에 값이 올라가지 않도록 배포 환경변수/secret manager에 넣는다.

## Frontend Work

### 1. Google Client ID 설정

프론트 `.env` 예:

```env
VITE_GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
VITE_API_BASE_URL=http://localhost:8080
```

운영 예:

```env
VITE_GOOGLE_CLIENT_ID=xxxx.apps.googleusercontent.com
VITE_API_BASE_URL=https://api.inninglog.com
```

### 2. Google Identity Services 스크립트 로드

HTML에 Google Identity Services 스크립트를 로드한다.

```html
<script src="https://accounts.google.com/gsi/client" async defer></script>
```

React/Vite에서는 `index.html`에 넣거나, 필요한 페이지에서 동적으로 로드한다.

### 3. Google 버튼 렌더링

JavaScript callback 방식 예:

```ts
declare global {
  interface Window {
    google?: any;
  }
}

function initializeGoogleLogin() {
  window.google.accounts.id.initialize({
    client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID,
    callback: handleGoogleCredential,
  });

  window.google.accounts.id.renderButton(
    document.getElementById("google-login-button"),
    {
      type: "standard",
      theme: "outline",
      size: "large",
      text: "signin_with",
      shape: "rectangular",
    },
  );
}
```

버튼 위치:

```html
<div id="google-login-button"></div>
```

### 4. Credential을 백엔드로 전달

Google callback에서 받은 `response.credential`을 백엔드로 보낸다.

```ts
async function handleGoogleCredential(response: { credential: string }) {
  const result = await fetch(`${import.meta.env.VITE_API_BASE_URL}/api/auth/google`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      credential: response.credential,
    }),
  });

  if (!result.ok) {
    throw new Error("Google login failed");
  }

  const loginResponse = await result.json();

  localStorage.setItem("accessToken", loginResponse.accessToken);

  if (loginResponse.user.onboardingCompleted) {
    window.location.href = "/main";
    return;
  }

  window.location.href = "/onboarding";
}
```

초기 MVP에서는 `localStorage` 저장으로 시작할 수 있다.
추후 보안을 강화할 때는 refresh token + HttpOnly cookie 구조를 별도로 설계한다.

### 5. 인증 API 호출

백엔드 보호 API를 호출할 때는 Inning Log JWT를 `Authorization` 헤더에 넣는다.

```ts
const accessToken = localStorage.getItem("accessToken");

const response = await fetch(`${import.meta.env.VITE_API_BASE_URL}/api/auth/me`, {
  headers: {
    Authorization: `Bearer ${accessToken}`,
  },
});
```

### 6. 로그아웃

현재 백엔드는 stateless access token 구조다.
프론트는 저장된 token을 제거하면 된다.

```ts
localStorage.removeItem("accessToken");
window.google?.accounts.id.disableAutoSelect();
```

Google 계정 연결 철회까지 하려면 Google Identity Services의 revoke 기능을 별도로 붙인다.

## CORS Checklist

프론트와 백엔드 origin이 다르면 CORS 설정이 필요하다.

예:

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
```

현재 백엔드는 `cors(Customizer.withDefaults())`만 켜져 있다.
프론트 연동 중 CORS 오류가 나면 허용 origin 설정을 명시적으로 추가해야 한다.

추가가 필요할 수 있는 값:

```text
http://localhost:5173
http://localhost:3000
https://inninglog.com
```

## Common Errors

### `401 INVALID_GOOGLE_TOKEN`

가능 원인:

- 프론트의 `VITE_GOOGLE_CLIENT_ID`와 백엔드의 `GOOGLE_CLIENT_ID`가 다름
- Google credential이 아니라 access token을 보냄
- 토큰이 만료됨
- Google OAuth client가 Web application 타입이 아님

### Google 버튼이 안 뜸

확인:

- `https://accounts.google.com/gsi/client` 스크립트가 로드됐는지
- Authorized JavaScript origins에 현재 프론트 origin이 등록됐는지
- localhost라면 포트까지 맞는지

### `redirect_uri_mismatch`

현재 추천 방식에서는 redirect URI를 사용하지 않는다.
이 오류가 난다면 프론트가 redirect UX mode 또는 HTML `data-login_uri` 방식으로 구현된 것이다.
JavaScript callback 방식으로 바꾸거나, 사용하는 redirect URI를 Google Console에 정확히 등록해야 한다.

## Final Integration Checklist

Google Cloud:

- [ ] Google Cloud 프로젝트 생성
- [ ] OAuth branding/consent screen 설정
- [ ] Web application OAuth client 생성
- [ ] Authorized JavaScript origins 등록
- [ ] 최소 scope만 사용: `openid`, `profile`, `email`

Backend:

- [ ] `GOOGLE_CLIENT_ID` 설정
- [ ] `JWT_SECRET` 운영용 강한 값으로 설정
- [ ] `/api/auth/google` Swagger에서 확인
- [ ] `/api/auth/me` Bearer token으로 확인

Frontend:

- [ ] `VITE_GOOGLE_CLIENT_ID` 설정
- [ ] `VITE_API_BASE_URL` 설정
- [ ] Google Identity Services script 로드
- [ ] Google button 렌더링
- [ ] `response.credential`을 `/api/auth/google`로 전송
- [ ] 반환된 `accessToken` 저장
- [ ] `onboardingCompleted` 기준으로 화면 분기

## References

- Google Identity Services setup: https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid
- Display Sign in with Google button: https://developers.google.com/identity/gsi/web/guides/display-button
- Verify Google ID token server-side: https://developers.google.com/identity/gsi/web/guides/verify-google-id-token
- Google OAuth client management: https://support.google.com/cloud/answer/15549257
