create table profile_image_uploads (
    id uuid primary key,
    user_id bigint not null references app_users(id),
    content_type varchar(50) not null,
    size_bytes bigint not null check (size_bytes > 0),
    status varchar(20) not null check (status in ('PENDING', 'READY', 'RETIRED')),
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    staging_deleted boolean not null default false
);
create index idx_profile_image_uploads_user on profile_image_uploads(user_id);
create index idx_profile_image_uploads_expiry on profile_image_uploads(expires_at);

