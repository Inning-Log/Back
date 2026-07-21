-- noinspection SqlNoDataSourceInspection
alter table app_users add column favorite_team_id bigint;

alter table app_users
    add constraint fk_app_users_favorite_team
    foreign key (favorite_team_id) references kbo_teams (id);

update app_users
set onboarding_completed = false
where favorite_team_id is null;
