# 이닝 기록 영상 API

이닝 기록 1개에 영상 1개를 붙인다. 파일은 서버를 거치지 않고 **Presigned URL로 S3에 직접** 올리고 내려받는다. 서버는 소유권, 업로드 상태, 친구 열람 권한만 관리한다.

## 업로드 흐름

1. 이닝 기록을 먼저 만든다 (`POST /api/inning-records`).
2. `POST /api/inning-records/{recordId}/video/upload-url`

   ```json
   { "contentType": "video/mp4", "contentLength": 10485760 }
   ```

   응답:

   ```json
   {
     "videoId": 1,
     "recordId": 789,
     "uploadUrl": "https://<bucket>.s3.ap-northeast-2.amazonaws.com/inning-records/users/12/records/789/<uuid>.mp4?X-Amz-...",
     "method": "PUT",
     "headers": { "content-type": "video/mp4" },
     "expiresAt": "2026-10-23T05:10:00Z"
   }
   ```

3. 프론트가 `uploadUrl`로 파일을 `PUT`한다. `headers`의 값을 **그대로** 보내야 서명이 맞는다. 본문은 파일 바이트 그대로 (multipart 아님).
4. `POST /api/inning-records/{recordId}/video/complete`. 서버가 S3에서 파일 크기·형식을 확인하고 `READY`로 바꾼다. 반복 호출해도 같은 결과다.

완료 전에 upload-url을 다시 요청하면 이전 미완료 업로드는 버리고 새 URL을 준다. 이미 `READY` 영상이 있으면 409이며, 삭제 후 다시 올린다.

## 조회·삭제

| 메서드·경로 | 의미 | 성공 |
| --- | --- | --- |
| `GET /api/inning-records/{recordId}/video` | 영상 조회. READY면 `downloadUrl`(기본 10분 유효) 포함 | 200 |
| `DELETE /api/inning-records/{recordId}/video` | 내 영상 삭제. 이닝 기록은 남는다. 반복 삭제도 204 | 204 |

- 조회 권한은 타임라인과 같다: 본인 또는 현재 `ACCEPTED` 친구. 친구에게는 업로드 중(`PENDING`) 영상이 보이지 않는다.
- 업로드·완료·삭제는 작성자만 가능하다. 그 외에는 404.
- `downloadUrl`은 만료되므로 저장하지 말고 재생할 때마다 조회한다.
- 이닝 기록을 삭제하면 영상도 함께 삭제된다.
- 이닝 기록 응답의 `videoStatus`: `UNAVAILABLE`(영상 없음), `PENDING`(업로드 확인 전), `READY`(조회 가능).

## 오류

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 지원하지 않는 형식(mp4, mov, webm 외), 크기 0 이하 등 |
| 404 | RECORD_NOT_FOUND / VIDEO_NOT_FOUND | 기록·영상 없음 또는 권한 없음 |
| 409 | VIDEO_ALREADY_EXISTS | 이미 READY 영상이 있음 |
| 409 | VIDEO_NOT_UPLOADED | complete 시점에 S3에 파일이 없음 |
| 409 | VIDEO_UPLOAD_MISMATCH | 올린 파일의 크기·형식이 요청과 다름. 업로드가 폐기되므로 upload-url부터 다시 |
| 413 | VIDEO_TOO_LARGE | 최대 크기 초과 (기본 500MB) |
| 503 | VIDEO_STORAGE_DISABLED | 서버에 영상 저장소가 설정되지 않음 |

## 서버 설정

기본값은 꺼져 있어 로컬 개발과 테스트에 AWS가 필요 없다.

```env
VIDEO_S3_ENABLED=true
VIDEO_S3_BUCKET=inning-log-videos
VIDEO_S3_REGION=ap-northeast-2
# 선택: VIDEO_S3_KEY_PREFIX, VIDEO_UPLOAD_URL_TTL(10m), VIDEO_DOWNLOAD_URL_TTL(10m), VIDEO_MAX_SIZE(500MB), VIDEO_ALLOWED_CONTENT_TYPES
```

AWS 자격증명은 기본 체인(EC2 인스턴스 역할, 로컬은 AWS 프로필)을 쓴다. 필요한 권한은 버킷의 `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`이다 (HeadObject는 GetObject 권한으로 된다).

S3 버킷 설정:

- 퍼블릭 액세스는 모두 차단한다. 접근은 Presigned URL로만 한다.
- 브라우저에서 직접 PUT/GET하므로 CORS를 설정한다.

  ```json
  [{ "AllowedOrigins": ["https://<프론트 도메인>", "http://localhost:5173"],
     "AllowedMethods": ["PUT", "GET"], "AllowedHeaders": ["*"], "MaxAgeSeconds": 3000 }]
  ```

- 삭제 실패나 완료되지 않은 업로드로 남은 파일은 DB에서 참조되지 않는다. 필요하면 S3 수명 주기 규칙으로 정리한다.

## 구현 범위 밖

- 썸네일 생성, 인코딩/압축, 내 영상과 친구 영상 합성
- 관람 기록 삭제나 회원 탈퇴로 이닝 기록이 함께 지워질 때의 S3 파일 정리 (DB에서는 접근 불가가 된다)
- 경기 응답의 `recordingState`는 그대로 `UNAVAILABLE`
