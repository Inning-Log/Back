-- noinspection SqlNoDataSourceInspection

alter table auth_refresh_tokens
alter column token_hash type varchar(64);