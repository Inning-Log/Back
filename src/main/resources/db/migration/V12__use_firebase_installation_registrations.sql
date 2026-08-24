-- noinspection SqlNoDataSourceInspection
alter table user_push_tokens
    drop constraint uk_user_push_tokens_push_token;

alter table user_push_tokens
    drop constraint uk_user_push_tokens_device;

alter table user_push_tokens
    drop constraint fk_user_push_tokens_user;

drop index idx_user_push_tokens_user_enabled;

alter table user_push_tokens
    drop column push_token;

alter table user_push_tokens
    add column registration_revision bigint not null default 1;

alter table user_push_tokens
    rename column device_id to installation_id;

alter table user_push_tokens
    rename to user_push_registrations;

alter table user_push_registrations
    add constraint uk_user_push_registrations_installation unique (installation_id);

alter table user_push_registrations
    add constraint fk_user_push_registrations_user
        foreign key (user_id) references app_users (id);

create index idx_user_push_registrations_user_enabled
    on user_push_registrations (user_id, enabled);
