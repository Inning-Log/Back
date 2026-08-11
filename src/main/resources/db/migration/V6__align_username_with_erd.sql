-- noinspection SqlNoDataSourceInspection
alter table app_users alter column username type varchar(30);

alter table app_users
    add constraint chk_app_users_username_lower
    check (username is null or username = lower(username));

alter table app_users
    add constraint chk_app_users_username_format
    check (username is null or username ~ '^[a-z0-9._]+$');
