# Back
Inning Log backend repository

## Home, games, and viewing logs

Authenticated users can query `GET /api/home/calendar`, `GET /api/home/win-rate`,
and `GET /api/games`, and manage their stadium/home viewing records through
`/api/user-game-logs`. The win rate uses the current KST regular season and the
team selected for each viewing. See [API contract and ingestion setup](docs/home-game-api.md).

Game snapshots are stored in PostgreSQL by an optional SQS consumer, disabled by
default. Apply the matching crawler changes when enabling ingestion. Video
availability is reported as `UNAVAILABLE` until the recording domain is connected.

## Local setup

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

- API health check: `GET /api/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

The `local` profile enables `POST /api/auth/dev-token` for development JWT issuance.
In shared environments, keep `APP_AUTH_DEV_TOKEN_ENABLED=false` and set a strong
`JWT_SECRET` value with at least 32 bytes.

## Google login

The frontend owns the Google login UI and Google Identity Services SDK flow.
After Google returns an ID token credential, send it to the backend:

```http
POST /api/auth/google
Content-Type: application/json

{
  "credential": "GOOGLE_ID_TOKEN"
}
```

The backend verifies the Google ID token, creates or finds the local user, and
returns the Inning Log JWT. The frontend can check username availability with
the returned access token:

```http
GET /api/auth/profile/username-availability?username=inning-user
Authorization: Bearer INNING_LOG_JWT
```

```json
{
  "username": "inning-user",
  "available": true
}
```

After the availability check, set the unique username and non-unique nickname
together:

```http
PUT /api/auth/profile
Authorization: Bearer INNING_LOG_JWT
Content-Type: application/json

{
  "username": "inning-user",
  "nickname": "Inning Logger"
}
```

## Onboarding

The screen-by-screen onboarding API persists each step independently, so a user
can resume from the last completed screen. All endpoints require the Inning Log
JWT returned by Google login.

```http
GET /api/onboarding
GET /api/onboarding/username-availability?username=inning.user
PUT /api/onboarding/username
PUT /api/onboarding/nickname
PUT /api/onboarding/favorite-team
Authorization: Bearer INNING_LOG_JWT
```

`GET /api/onboarding` returns `nextStep` as `USERNAME`, `NICKNAME`,
`FAVORITE_TEAM`, or `COMPLETED`. Usernames are stored without the UI's `@`
prefix, normalized to lowercase, limited to 30 characters, and may contain only
letters, numbers, periods, and underscores. `GET /api/teams` supplies the
active team list for the final screen.

## My page

```http
GET /api/mypage
GET /api/mypage/username-availability?username=inning.user
PATCH /api/mypage/profile
PUT /api/mypage/favorite-team
PUT /api/mypage/profile-image
Authorization: Bearer INNING_LOG_JWT
```

The profile response includes the selected team's display information. Send a
null or blank `profileImageUrl` to reset the custom profile image.

Configure the backend with:

```env
GOOGLE_CLIENT_ID=your-google-oauth-client-id
JWT_SECRET=replace-with-a-strong-secret-at-least-32-bytes
```

For Google Cloud Console, use the minimum login scopes: `openid`, `profile`,
and `email`. Store the Google `sub` value as the provider user identifier.

## Timeline and inning records

Game-specific timelines use `GET /api/timelines/me?gameId=123` and
`GET /api/timelines/{userId}?gameId=123`. Accepted friends may read records;
only the owner can create, edit, or delete them. Multiple records in one inning
are supported. See [Timeline API and frontend routing contract](docs/timeline-api.md)
for record endpoints, pagination, viewing prerequisites, and video integration limits.

## Push notifications

FCM delivery is disabled by default, so local development and tests do not need
Firebase credentials. Enable it with Application Default Credentials:

```env
FIREBASE_ENABLED=true
FIREBASE_PROJECT_ID=your-firebase-project-id
GOOGLE_APPLICATION_CREDENTIALS=/path/outside/the/repository/service-account.json
```

Authenticated clients register and disable their current installation through:

```http
PUT /api/notifications/push-token
DELETE /api/notifications/push-token?deviceId=installation-id
Authorization: Bearer INNING_LOG_JWT
```

`deviceId` is the Firebase Installation ID (FID), while `pushToken` is the FCM
registration token. They are intentionally different values. Backend domain use
cases enqueue a typed notification with `NotificationQueueService.enqueueToUser`
inside their business transaction. FCM delivery then runs outside that
transaction through a target-level outbox, processing lease, bounded retry, and
invalid-token cleanup.

The queue infrastructure does not create product events on its own. Friend,
timeline, game, reminder, preference, and notification-inbox domains still need
to call the queue as they are implemented. See:

- [Frontend Firebase integration](docs/firebase-push-frontend-integration.md)
- [Backend notification catalog and operations](docs/notification-backend-spec.md)

