# Back
Inning Log backend repository

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

Configure the backend with:

```env
GOOGLE_CLIENT_ID=your-google-oauth-client-id
JWT_SECRET=replace-with-a-strong-secret-at-least-32-bytes
```

For Google Cloud Console, use the minimum login scopes: `openid`, `profile`,
and `email`. Store the Google `sub` value as the provider user identifier.

