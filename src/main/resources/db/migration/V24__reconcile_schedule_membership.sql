-- Withdrawing a schedule never deletes a game or its recordings/results.
alter table games add column schedule_active boolean not null default true;
alter table games add column schedule_membership_observed_at timestamp(6) with time zone;

create table game_schedule_coverage (
    month_key varchar(7) not null,
    game_type varchar(30) not null,
    observed_at timestamp(6) with time zone not null,
    primary key (month_key, game_type)
);

-- A postseason opponent may be TBD while the league's reset time is already known.
create table game_unconfirmed_starts (
    game_date date not null,
    game_type varchar(30) not null,
    scheduled_at timestamp(6) with time zone not null,
    primary key (game_date, game_type)
);
