# 경기 영상 저장 API

한 경기의 이닝 영상을 9:16(1080×1920) 영상 하나로 합친다. 친구 영상도 함께 넣을 수 있다.
인프라 설정은 [AWS 설정 가이드](game-video-aws-setup.md)를 본다.

## 흐름

1. `GET /api/games/{gameId}/video/friends`로 함께 넣을 수 있는 친구를 보여준다.
2. `POST /api/games/{gameId}/video` `{"friendIds":[12, 34]}` → 202. 비우거나 body가 없으면 내 영상만.
3. 완료되면 `GENERATED_VIDEO_READY` 알림(data: `gameId`, `gameVideoId`, `link`). 실패하면 `GENERATED_VIDEO_FAILED`.
4. `GET /api/games/{gameId}/video`에서 `status`가 `READY`면 `downloadUrl`로 받는다. 만료되면 다시 조회한다.

## 영상 구성

- 이닝(회차 + 초/말)마다 구간 하나. 구간 길이는 가장 긴 카드에 맞추고, 짧은 카드는 마지막 프레임에서 멈춘다.
- 참가자마다 카드 하나. 첫 카드는 요청한 사람. 그 이닝에 영상이 없으면 "이 시간대 기록 없음" 카드.
- 같은 사람이 한 이닝에 여러 개 찍었으면 촬영 순서대로 이어서 재생하고, 각 영상의 시간·캡션·점수가 그때 바뀐다.
- 소리는 첫 카드(없으면 다음 카드)의 소리.
- 3명까지는 1열, 4~6명은 2열. 영상은 늘리지 않고 카드에 맞게 잘라 채운다.
- 출력: H.264 High 30fps, AAC 48kHz 스테레오, faststart.

## 응답 `GameVideoResponse`

| 필드 | 설명 |
|---|---|
| `gameVideoId`, `gameId` | |
| `status` | `RENDERING` → `MERGING` → `READY` 또는 `FAILED` |
| `mode` | `SOLO` / `WITH_FRIENDS` |
| `memberUserIds` | 카드 순서 |
| `segmentCount`, `readySegmentCount` | 진행률 표시용 |
| `durationSeconds` | READY일 때 |
| `failureReason` | `SEGMENT_FAILED`, `TIMEOUT`, `FFMPEG_FAILED`, `SOURCE_MISSING` 등 |
| `downloadUrl`, `expiresAt` | READY일 때 |

## 오류

| 상태 | code | 의미 |
|---|---|---|
| 400 | `INVALID_FRIEND` | 수락한 친구가 아니거나 이 경기 영상이 없는 친구 |
| 400 | `TOO_MANY_FRIENDS` | 친구 5명 초과 (`GAME_VIDEO_MAX_FRIENDS`) |
| 404 | `GAME_NOT_FOUND`, `GAME_LOG_NOT_FOUND`, `GAME_VIDEO_NOT_FOUND` | |
| 409 | `GAME_VIDEO_IN_PROGRESS` | 이미 만드는 중 |
| 409 | `NO_CLIPS` | 완료된 영상이 하나도 없음 |
| 503 | `GAME_VIDEO_DISABLED` | 서버에 영상 처리가 설정되지 않음 |

새로 요청하면 이전 완료 영상은 새 영상으로 바뀐다. 바뀌지 않은 이닝은 다시 렌더링하지 않아서 빠르다.
