Project inning_log {
database_type: 'PostgreSQL'
Note: '''
Inning Log MVP ERD 재검토 반영본
기준일: 2026-08-03

app_users.id는 내부 불변 PK이고 username은 공개 @아이디이다.
kbo_teams.code는 팀이 합의한 고정 코드로 사용한다.
game_inning_states는 제거하고 inning_records에 이닝 번호와 초/말을 직접 저장한다.
video_merge_job_inputs는 제거하고 프로젝트 제출 이후 참여자와 클립을 불변으로 관리한다.

삭제 정책: 인증·알림설정·푸시토큰은 User와 CASCADE 가능, 사용자 콘텐츠는 RESTRICT + soft delete.
user_game_logs와 inning_records는 물리 CASCADE하지 않고 참조 중인 media_files도 물리 삭제하지 않는다.
프로젝트 제출 후 참여자와 클립은 수정하지 않으며 merge 성공 결과는 Job과 Project에 함께 연결한다.
'''
}

Enum login_provider {
GOOGLE
}

Enum external_provider {
KBO
NAVER
}

Enum game_status {
SCHEDULED
LIVE
FINISHED
CANCELED
POSTPONED
SUSPENDED
}

Enum game_type {
REGULAR
WILDCARD
SEMI_PLAYOFF
PLAYOFF
KOREAN_SERIES
EXHIBITION
}

Enum inning_half {
TOP
BOTTOM
}

Enum viewing_type {
STADIUM
HOME
}

Enum media_type {
RAW_CLIP
MERGED_VIDEO
THUMBNAIL
}

Enum media_status {
PENDING_UPLOAD
READY
FAILED
}

Enum video_project_status {
DRAFT
QUEUED
PROCESSING
READY
FAILED
}

Enum merge_job_status {
QUEUED
PROCESSING
COMPLETED
FAILED
}

Enum friendship_status {
PENDING
ACCEPTED
REJECTED
}

Enum notification_type {
FRIEND_REQUEST
FRIEND_ACCEPTED
GAME_PROGRESS
RECORD_REMINDER
GENERATED_VIDEO_READY
GENERATED_VIDEO_FAILED
}

Enum device_platform {
IOS
ANDROID
WEB
}

// ======================================================
// 1. APP USERS
// ======================================================

Table app_users {
id bigint [pk, increment, not null, note: '내부 관계용 불변 PK']
username varchar(30) [unique, note: '공개 @아이디. MVP에서는 소문자만 허용하고 탈퇴 후에도 재사용하지 않음']
email varchar(320) [not null, note: '앱 사용자 대표 이메일. 로그인 키로 사용하지 않음']
nickname varchar(80) [note: '화면 표시 이름']
profile_image_url varchar(500)
role varchar(30) [not null, default: 'USER']
favorite_team_id bigint [ref: > kbo_teams.id, note: '현재 최애팀']
onboarding_completed boolean [not null, default: false]
created_at timestamptz [not null]
updated_at timestamptz [not null]
deleted_at timestamptz [note: '사용자 소프트 삭제 시각']

checks {
`username IS NULL OR username = lower(username)` [name: 'chk_app_users_username_lower']
`username IS NULL OR username ~ '^[a-z0-9._]+$'` [name: 'chk_app_users_username_format']
}

Note: '''
username은 onboarding 전 NULL을 허용한다.
MVP에서는 소문자만 허용하고 탈퇴 후 사칭 방지를 위해 재사용하지 않는다.
username 변경 허용 여부는 API 정책으로 제한한다.
'''
}

// ======================================================
// 2. OAUTH ACCOUNTS
// ======================================================

Table oauth_accounts {
id bigint [pk, increment, not null]
user_id bigint [not null, ref: > app_users.id]
provider login_provider [not null]
provider_user_id varchar(120) [not null, note: 'Google sub']
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(provider, provider_user_id) [unique, name: 'uq_oauth_accounts_provider_user']
(user_id, provider) [unique, name: 'uq_oauth_accounts_user_provider']
}

Note: '''
대표 이메일은 app_users.email에서 관리한다.
외부 계정의 로그인 식별자는 provider + provider_user_id이다.
'''
}

// ======================================================
// 3. AUTH REFRESH TOKENS
// ======================================================

Table auth_refresh_tokens {
id bigint [pk, increment, not null]
user_id bigint [not null, ref: > app_users.id]
token_hash char(64) [not null, unique, note: 'Refresh Token SHA-256. 원문 저장 금지']
expires_at timestamptz [not null]
revoked_at timestamptz [note: '로그아웃 또는 회전 시 폐기 시각']
last_used_at timestamptz [note: '마지막 정상 사용 시각']
created_at timestamptz [not null]

indexes {
(user_id, expires_at) [name: 'idx_auth_refresh_tokens_user_expiry']
expires_at [name: 'idx_auth_refresh_tokens_expiry']
}
}

// ======================================================
// 4. STADIUMS
// ======================================================

Table stadiums {
id bigint [pk, increment, not null]
name varchar(100) [not null]
city varchar(50) [not null]
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(city, name) [unique, name: 'uq_stadiums_city_name']
}
}

// ======================================================
// 5. KBO TEAMS
// ======================================================

Table kbo_teams {
id bigint [pk, increment, not null, note: '내부 FK용 불변 PK']
code varchar(10) [not null, unique, note: '팀이 합의한 고정 코드: LG, OB, SK, HT, SS, NC, KT, HH, LT, WO']
name varchar(50) [not null, unique]
short_name varchar(20) [not null]
logo_url varchar(500)
primary_color varchar(7) [note: 'HEX #RRGGBB']
home_stadium_id bigint [ref: > stadiums.id, note: '현재 기본 홈구장']
display_order int [not null]
active boolean [not null, default: true]
created_at timestamptz [not null]
updated_at timestamptz [not null]

checks {
`primary_color IS NULL OR primary_color ~ '^#[0-9A-Fa-f]{6}$'` [name: 'chk_kbo_teams_primary_color']
`display_order >= 0` [name: 'chk_kbo_teams_display_order']
}

Note: '''
code는 이번 MVP에서 외부 경기 ID 파싱에도 공유하는 고정값으로 사용한다.
제공처별 코드가 실제로 달라지는 사례가 확인되기 전까지 별도 매핑 테이블을 만들지 않는다.
'''
}

// ======================================================
// 6. GAME EXTERNAL IDS
// ======================================================

Table game_external_ids {
id bigint [pk, increment, not null]
game_id bigint [not null, ref: > games.id]
provider external_provider [not null]
external_id varchar(100) [not null, note: '외부 경기 ID 원문']
last_seen_at timestamptz [note: '크롤링 응답에서 마지막으로 발견한 시각']
last_synced_at timestamptz [note: 'games 반영에 마지막으로 성공한 시각']
source_updated_at timestamptz [note: '제공처가 수정 시각을 제공할 때 저장']
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(provider, external_id) [unique, name: 'uq_game_external_ids_provider_external']
(game_id, provider) [unique, name: 'uq_game_external_ids_game_provider']
}

Note: '''
크롤링 upsert의 1순위 식별자는 provider + external_id이다.
외부 ID 문자열의 파싱 결과는 game_type과 game_sequence 교차 검증에만 사용한다.
'''
}

// ======================================================
// 7. GAMES
// ======================================================

Table games {
id bigint [pk, increment, not null]
season_year int [not null]
game_type game_type [not null, default: 'REGULAR']
game_date date [not null, note: '한국 현지 경기 날짜']
game_sequence smallint [not null, default: 0, note: '0 일반, 1/2 더블헤더']
scheduled_at timestamptz
started_at timestamptz
ended_at timestamptz
home_team_id bigint [not null, ref: > kbo_teams.id]
away_team_id bigint [not null, ref: > kbo_teams.id]
stadium_id bigint [ref: > stadiums.id]
home_score int [note: '경기 전 NULL, 경기 시작 후 0 이상']
away_score int [note: '경기 전 NULL, 경기 시작 후 0 이상']
current_inning int [note: '현재 이닝. 1 이상']
current_half inning_half
status game_status [not null, default: 'SCHEDULED']
cancellation_reason varchar(100)
live_data_synced_at timestamptz [note: '점수와 현재 이닝 데이터의 마지막 정상 동기화 시각']
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(game_date, home_team_id, away_team_id, game_sequence, game_type) [unique, name: 'uq_games_schedule']
(status, game_date) [name: 'idx_games_status_date']
(home_team_id, game_date) [name: 'idx_games_home_team_date']
(away_team_id, game_date) [name: 'idx_games_away_team_date']
}

checks {
`home_team_id <> away_team_id` [name: 'chk_games_different_teams']
`home_score IS NULL OR home_score >= 0` [name: 'chk_games_home_score']
`away_score IS NULL OR away_score >= 0` [name: 'chk_games_away_score']
`current_inning IS NULL OR current_inning >= 1` [name: 'chk_games_current_inning']
`(current_inning IS NULL) = (current_half IS NULL)` [name: 'chk_games_current_inning_half_pair']
`game_sequence IN (0, 1, 2)` [name: 'chk_games_game_sequence']
}

Note: '''
현재 이닝의 단일 기준은 games.current_inning/current_half이다.
game_inning_states는 MVP에서 제거한다.
경기 갱신은 외부 ID 우선으로 수행하며 날짜 변경만으로 새 경기를 생성하지 않는다.
'''
}

// ======================================================
// 8. USER GAME LOGS
// ======================================================

Table user_game_logs {
id bigint [pk, increment, not null]
user_id bigint [not null, ref: > app_users.id]
game_id bigint [not null, ref: > games.id]
cheering_team_id bigint [not null, ref: > kbo_teams.id, note: '해당 경기를 볼 당시 응원팀 스냅샷']
viewing_type viewing_type [not null]
created_at timestamptz [not null]
updated_at timestamptz [not null]
deleted_at timestamptz [note: '소프트 삭제 시각']

indexes {
(user_id, game_id) [unique, name: 'uq_user_game_logs_user_game']
(user_id, viewing_type) [name: 'idx_user_game_logs_viewing_type']
(game_id, user_id) [name: 'idx_user_game_logs_game_user']
}

Note: '''
삭제 후 같은 경기 기록을 다시 만들면 새 행을 만들지 않고 기존 행을 복원한다.
cheering_team_id는 game의 home_team_id 또는 away_team_id인지 서비스에서 검증한다.
MVP 기록 열람은 ACCEPTED 친구에게 허용한다.
'''
}

// ======================================================
// 9. MEDIA FILES
// ======================================================

Table media_files {
id bigint [pk, increment, not null]
owner_user_id bigint [not null, ref: > app_users.id, note: '원본 및 생성 결과물의 소유 사용자']
media_type media_type [not null]
status media_status [not null, default: 'PENDING_UPLOAD']
storage_key varchar(500) [not null, unique, note: '비공개 Object Storage 내부 키']
mime_type varchar(100)
file_size_bytes bigint
duration_ms int
width int
height int
error_message text [note: '업로드 또는 파일 검증 실패 사유']
uploaded_at timestamptz
created_at timestamptz [not null]
updated_at timestamptz [not null]
deleted_at timestamptz [note: '논리 삭제 시각']

indexes {
(owner_user_id, media_type) [name: 'idx_media_files_owner_type']
(status, created_at) [name: 'idx_media_files_status_created']
}

checks {
`file_size_bytes IS NULL OR file_size_bytes >= 0` [name: 'chk_media_files_size']
`duration_ms IS NULL OR duration_ms >= 0` [name: 'chk_media_files_duration']
`width IS NULL OR width > 0` [name: 'chk_media_files_width']
`height IS NULL OR height > 0` [name: 'chk_media_files_height']
}

Note: '''
합성 처리 상태는 video_projects와 video_merge_jobs가 관리한다.
media_files는 파일 업로드 및 사용 가능 여부만 관리한다.
file_url은 저장하지 않고 storage_key로 서명 URL을 발급한다.
'''
}

// ======================================================
// 10. INNING RECORDS
// ======================================================

Table inning_records {
id bigint [pk, increment, not null]
user_game_log_id bigint [not null, ref: > user_game_logs.id]
inning_number int [not null, note: '1 이상, 연장 이닝 허용']
half inning_half [not null]
raw_video_file_id bigint [not null, unique, ref: > media_files.id]
caption varchar(255)
recorded_at timestamptz [not null, note: '클라이언트 실제 촬영 시각']
home_score_at_recording int [not null]
away_score_at_recording int [not null]
score_observed_at timestamptz [note: '점수 원본 데이터가 마지막으로 동기화된 시각']
created_at timestamptz [not null, note: '서버 레코드 생성 시각']
updated_at timestamptz [not null]
deleted_at timestamptz [note: '공동 영상 참조 보존을 위한 소프트 삭제']

indexes {
(user_game_log_id, inning_number, half) [unique, name: 'uq_inning_records_user_log_inning']
}

checks {
`inning_number >= 1` [name: 'chk_inning_records_inning_number']
`home_score_at_recording >= 0` [name: 'chk_inning_records_home_score']
`away_score_at_recording >= 0` [name: 'chk_inning_records_away_score']
}

Note: '''
game_inning_states를 참조하지 않고 이닝 번호와 초/말을 직접 스냅샷으로 저장한다.
한 사용자 경기 로그의 한 이닝당 레코드 1개를 유지하며 교체 시 기존 행을 갱신한다.
raw_video_file은 READY 상태이며 RAW_CLIP 타입인지 서비스에서 검증한다.
'''
}

// ======================================================
// 11. VIDEO PROJECTS
// ======================================================

Table video_projects {
id bigint [pk, increment, not null]
game_id bigint [not null, ref: > games.id]
owner_user_id bigint [not null, ref: > app_users.id]
status video_project_status [not null, default: 'DRAFT']
output_video_file_id bigint [ref: > media_files.id, note: '최근 성공한 MERGED_VIDEO']
thumbnail_file_id bigint [ref: > media_files.id, note: '최근 성공한 THUMBNAIL']
submitted_at timestamptz [note: '이 시점 이후 참여자와 클립 수정 금지']
completed_at timestamptz
created_at timestamptz [not null]
updated_at timestamptz [not null]
deleted_at timestamptz

indexes {
(owner_user_id, game_id) [name: 'idx_video_projects_owner_game']
(game_id, created_at) [name: 'idx_video_projects_game_created']
}

Note: '''
DRAFT에서만 participants와 clips를 수정할 수 있다.
제출 이후 프로젝트 입력은 불변이며 다른 구성은 새 프로젝트로 생성한다.
owner의 user_game_log도 participant에 반드시 포함한다.
'''
}

// ======================================================
// 12. VIDEO PROJECT PARTICIPANTS
// ======================================================

Table video_project_participants {
id bigint [pk, increment, not null]
video_project_id bigint [not null, ref: > video_projects.id]
user_game_log_id bigint [not null, ref: > user_game_logs.id]
display_order int [not null]
added_at timestamptz [not null]

indexes {
(video_project_id, user_game_log_id) [unique, name: 'uq_video_project_participants_log']
(video_project_id, display_order) [unique, name: 'uq_video_project_participants_order']
user_game_log_id [name: 'idx_video_project_participants_user_log']
}

checks {
`display_order >= 0` [name: 'chk_video_project_participants_order']
}

Note: '''
참여 로그는 project.game_id와 동일한 경기여야 한다.
소유자 본인이거나 ACCEPTED 친구인지 같은 트랜잭션에서 검증한다.
'''
}

// ======================================================
// 13. VIDEO PROJECT CLIPS
// ======================================================

Table video_project_clips {
id bigint [pk, increment, not null]
video_project_id bigint [not null, ref: > video_projects.id]
inning_record_id bigint [not null, ref: > inning_records.id]
clip_order int [not null, note: '확정된 합성 재생 순서']
selected_at timestamptz [not null]

indexes {
(video_project_id, inning_record_id) [unique, name: 'uq_video_project_clips_record']
(video_project_id, clip_order) [unique, name: 'uq_video_project_clips_order']
inning_record_id [name: 'idx_video_project_clips_inning_record']
}

checks {
`clip_order >= 0` [name: 'chk_video_project_clips_order']
}

Note: '''
inning_record.user_game_log_id는 project participant에 포함되어야 한다.
프로젝트 제출 후에는 클립을 수정하지 않는다.
권장 자동 정렬은 inning_number, half, participant.display_order 순이다.
'''
}

// ======================================================
// 14. VIDEO MERGE JOBS
// ======================================================

Table video_merge_jobs {
id bigint [pk, increment, not null]
video_project_id bigint [not null, ref: > video_projects.id]
requested_by_user_id bigint [not null, ref: > app_users.id]
attempt_no int [not null]
status merge_job_status [not null, default: 'QUEUED']
output_video_file_id bigint [unique, ref: > media_files.id, note: '이 작업이 생성한 MERGED_VIDEO']
thumbnail_file_id bigint [unique, ref: > media_files.id, note: '이 작업이 생성한 THUMBNAIL']
error_message text
locked_at timestamptz [note: 'Worker가 작업을 선점한 시각']
heartbeat_at timestamptz [note: '중단된 PROCESSING 작업 감지용']
started_at timestamptz
finished_at timestamptz
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(video_project_id, attempt_no) [unique, name: 'uq_video_merge_jobs_attempt']
(status, created_at) [name: 'idx_video_merge_jobs_worker_queue']
requested_by_user_id [name: 'idx_video_merge_jobs_requested_by']
}

checks {
`attempt_no >= 1` [name: 'chk_video_merge_jobs_attempt']
}

Note: '''
video_merge_job_inputs는 제거한다.
Worker는 제출 후 불변인 video_project_clips를 입력으로 사용한다.
재시도마다 새 Job 행을 생성하고 성공 결과 파일을 Job과 Project 양쪽에 연결한다.
'''
}

// ======================================================
// 15. FRIENDSHIPS
// ======================================================

Table friendships {
id bigint [pk, increment, not null]
requester_id bigint [not null, ref: > app_users.id]
receiver_id bigint [not null, ref: > app_users.id]
pair_key varchar(100) [not null, unique, note: 'DB generated: minUserId:maxUserId']
status friendship_status [not null, default: 'PENDING']
requested_at timestamptz [not null]
responded_at timestamptz
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(receiver_id, status) [name: 'idx_friendships_receiver_status']
(requester_id, status) [name: 'idx_friendships_requester_status']
}

checks {
`requester_id <> receiver_id` [name: 'chk_friendships_not_self']
}

Note: '''
pair_key는 애플리케이션 문자열 조합이 아니라 PostgreSQL generated column으로 생성한다.
BLOCKED는 MVP에서 제거한다. 차단 기능이 추가되면 단방향 user_blocks 테이블로 분리한다.
REJECTED 후 재신청은 기존 행을 PENDING으로 갱신한다.
'''
}

// ======================================================
// 16. NOTIFICATION SETTINGS
// ======================================================

Table notification_settings {
user_id bigint [pk, not null, ref: > app_users.id]
game_progress_enabled boolean [not null, default: true]
record_reminder_enabled boolean [not null, default: true]
updated_at timestamptz [not null]

Note: '''
댓글과 반응 기능은 MVP에서 제외하므로 comment_reaction_enabled를 제거한다.
설정은 앱 내 알림 생성이 아니라 푸시 발송 여부를 제어한다.
'''
}

// ======================================================
// 17. NOTIFICATIONS
// ======================================================

Table notifications {
id bigint [pk, increment, not null]
user_id bigint [not null, ref: > app_users.id]
type notification_type [not null]
actor_user_id bigint [ref: > app_users.id]
game_id bigint [ref: > games.id]
video_project_id bigint [ref: > video_projects.id]
title varchar(100) [not null]
body varchar(500)
read_at timestamptz [note: 'NULL이면 읽지 않음']
created_at timestamptz [not null]

indexes {
(user_id, created_at) [name: 'idx_notifications_user_created']
(user_id, read_at, created_at) [name: 'idx_notifications_user_read_created']
}

Note: '''
is_read는 read_at과 중복되므로 제거한다.
related_type/related_id 대신 실제 FK 컬럼을 사용한다.
마이그레이션에서는 read_at IS NULL인 사용자별 부분 인덱스를 추가한다.
'''
}

// ======================================================
// 18. USER PUSH TOKENS
// ======================================================

Table user_push_tokens {
id bigint [pk, increment, not null]
user_id bigint [not null, ref: > app_users.id]
platform device_platform [not null]
device_id varchar(255) [note: '앱 설치 또는 기기 식별자']
push_token varchar(500) [not null, unique, note: 'FCM/APNs 토큰']
enabled boolean [not null, default: true]
last_seen_at timestamptz [note: '만료 토큰 정리 기준']
created_at timestamptz [not null]
updated_at timestamptz [not null]

indexes {
(user_id, device_id) [unique, name: 'uq_user_push_tokens_user_device']
(user_id, enabled) [name: 'idx_user_push_tokens_user_enabled']
}

Note: '''
device_id가 NULL이면 PostgreSQL UNIQUE 특성상 여러 행을 허용한다.
동일 push_token이 다른 사용자로 로그인되면 기존 행의 소유자를 갱신한다.
'''
}
