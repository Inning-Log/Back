-- noinspection SqlNoDataSourceInspection
alter table app_users add column username varchar(80);

alter table app_users
    add constraint uk_app_users_username unique (username);
