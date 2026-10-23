-- Nullable expiry keeps rolling back to the previous application version safe.
alter table inning_record_videos add column upload_expires_at timestamp(6) with time zone;
alter table inning_record_videos add column storage_deleted_at timestamp(6) with time zone;
alter table inning_record_videos add column cleanup_retry_at timestamp(6) with time zone;
alter table inning_record_videos add column cleanup_attempts integer not null default 0;

-- Old rows did not retain the PUT deadline. Use the maximum supported URL lifetime.
update inning_record_videos set upload_expires_at = created_at + interval '7' day;
create index idx_video_storage_cleanup on inning_record_videos(storage_deleted_at, cleanup_retry_at, id);
