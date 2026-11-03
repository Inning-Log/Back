alter table game_state_snapshots add column previous_score_observed_at timestamp(6) with time zone;
alter table game_state_snapshots add column previous_home_score integer;
alter table game_state_snapshots add column previous_away_score integer;

alter table game_state_snapshots add constraint chk_previous_score_observation check (
    (previous_score_observed_at is null and previous_home_score is null and previous_away_score is null)
    or (previous_score_observed_at is not null and previous_home_score is not null and previous_away_score is not null
        and previous_home_score >= 0 and previous_away_score >= 0 and previous_score_observed_at < observed_at)
);

-- Older rows only retained changes. Do not invent a last unchanged observation.
-- Existing (game_id, observed_at, id) index serves the score history query.
