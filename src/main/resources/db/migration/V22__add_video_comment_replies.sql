alter table video_comments add column parent_comment_id bigint;
alter table video_comments add constraint uq_video_comment_id_video unique (id, video_id);
alter table video_comments add constraint fk_video_comment_parent
    foreign key (parent_comment_id, video_id) references video_comments(id, video_id);
alter table video_comments add constraint ck_video_comment_not_self_parent
    check (parent_comment_id is null or parent_comment_id <> id);
create index idx_video_comment_parent on video_comments(parent_comment_id, deleted_at, id);
