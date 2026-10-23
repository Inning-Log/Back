package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.video.repository.VideoRepository;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional
@TestPropertySource(properties = "app.notifications.frontend-origin=https://inning-log.vercel.app")
class VideoCommentApiIntegrationTest extends GameIntegrationSupport {
    @Autowired VideoRepository videos;
    @Autowired FriendshipRepository friendships;

    @Test
    void friendCreatesListsEditsDeletesAndNotifiesOnlyOnce() throws Exception {
        var fixture = fixture(true);
        String clientId = UUID.randomUUID().toString();
        var created = create(fixture.friend(), fixture.videoId(), clientId, "  멋진 영상!  ", 201);
        long id = created.path("id").longValue();
        assertThat(created.path("videoId").longValue()).isEqualTo(fixture.videoId());
        assertThat(created.path("author").path("userId").longValue()).isEqualTo(fixture.friend().getId());
        assertThat(created.path("content").stringValue()).isEqualTo("멋진 영상!");
        assertThat(created.path("isMine").booleanValue()).isTrue();
        assertThat(create(fixture.friend(), fixture.videoId(), clientId, "멋진 영상!", 200).path("id").longValue()).isEqualTo(id);
        assertThat(notificationCount(fixture.owner())).isEqualTo(1);
        assertThat(outboxCount(fixture.owner())).isEqualTo(1);
        String payload = jdbc.queryForObject("select data_json from notifications where user_id = ?", String.class, fixture.owner().getId());
        var data = mapper.readTree(payload);
        assertThat(data.path("videoId").stringValue()).isEqualTo(Long.toString(fixture.videoId()));
        assertThat(data.path("recordId").stringValue()).isEqualTo(Long.toString(fixture.recordId()));
        assertThat(data.path("commentId").stringValue()).isEqualTo(Long.toString(id));
        assertThat(data.path("ownerUserId").stringValue()).isEqualTo(fixture.owner().getId().toString());
        assertThat(data.path("link").stringValue()).isEqualTo("https://inning-log.vercel.app/timeline/history/"
                + data.path("gameId").stringValue() + "?recordId=" + fixture.recordId()
                + "&videoId=" + fixture.videoId() + "&commentId=" + id);
        assertThat(payload).doesNotContain("멋진 영상");

        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(patch(commentPath(id)).with(as(fixture.friend())).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("content", "수정한 댓글"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("수정한 댓글"));
        // A retry of the original create does not revert an edit or emit another notification.
        assertThat(create(fixture.friend(), fixture.videoId(), clientId, "멋진 영상!", 200)
                .path("content").stringValue()).isEqualTo("수정한 댓글");
        mvc.perform(get(commentPath(id)).with(as(fixture.owner()))).andExpect(status().isOk());
        mvc.perform(delete(commentPath(id)).with(as(fixture.friend()))).andExpect(status().isNoContent());
        mvc.perform(delete(commentPath(id)).with(as(fixture.friend()))).andExpect(status().isNoContent());
        mvc.perform(get(commentPath(id)).with(as(fixture.owner()))).andExpect(status().isNotFound());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        create(fixture.friend(), fixture.videoId(), clientId, "멋진 영상!", 409);
        assertThat(notificationCount(fixture.owner())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select content from video_comments where id = ?", String.class, id)).isEqualTo("[삭제됨]");
    }

    @Test
    void ownerMayCommentWithoutSelfNotificationAndCannotEditFriendsComment() throws Exception {
        var fixture = fixture(true);
        create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "내 영상", 201);
        assertThat(notificationCount(fixture.owner())).isZero();
        long friendComment = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "친구 댓글", 201).path("id").longValue();
        mvc.perform(patch(commentPath(friendComment)).with(as(fixture.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"위조\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(commentPath(friendComment)).with(as(fixture.owner()))).andExpect(status().isNotFound());
    }

    @Test
    void nonFriendsPendingRequestsAndAnonymousUsersCannotAccess() throws Exception {
        var fixture = fixture(false);
        var pending = Friendship.request(fixture.owner(), fixture.friend(), NOW);
        friendships.saveAndFlush(pending);
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.friend()))).andExpect(status().isNotFound());
        create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "권한 없음", 404);
        mvc.perform(get(listPath(fixture.videoId()))).andExpect(status().isUnauthorized());
        assertThat(notificationCount(fixture.owner())).isZero();
    }

    @Test
    void losingFriendshipRevokesAllCommentAccess() throws Exception {
        var fixture = fixture(true);
        long id = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "댓글", 201).path("id").longValue();
        jdbc.update("delete from friendships where requester_id = ? and receiver_id = ?", fixture.owner().getId(), fixture.friend().getId());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.friend()))).andExpect(status().isNotFound());
        mvc.perform(get(commentPath(id)).with(as(fixture.friend()))).andExpect(status().isNotFound());
        mvc.perform(patch(commentPath(id)).with(as(fixture.friend())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"수정\"}")).andExpect(status().isNotFound());
        mvc.perform(delete(commentPath(id)).with(as(fixture.friend()))).andExpect(status().isNotFound());
        create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "다시 댓글", 404);
    }

    @Test
    void onlyReadyAndActiveVideoParentsAreVisibleAndReplacementStartsEmpty() throws Exception {
        var fixture = fixture(true);
        long id = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "원본 댓글", 201).path("id").longValue();
        videos.delete(fixture.videoId(), NOW);
        long replacement = videos.insertPending(fixture.recordId(), "test/" + UUID.randomUUID(), "video/mp4", 10, NOW);
        mvc.perform(get(listPath(replacement)).with(as(fixture.owner()))).andExpect(status().isNotFound());
        create(fixture.owner(), replacement, UUID.randomUUID().toString(), "아직 준비 안 됨", 404);
        videos.markReady(replacement, NOW);
        mvc.perform(get(listPath(replacement)).with(as(fixture.friend())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner()))).andExpect(status().isNotFound());
        mvc.perform(get(commentPath(id)).with(as(fixture.friend()))).andExpect(status().isNotFound());
        jdbc.update("update inning_records set deleted_at = ? where id = ?", Timestamp.from(NOW), fixture.recordId());
        mvc.perform(get(listPath(replacement)).with(as(fixture.owner()))).andExpect(status().isNotFound());
    }

    @Test
    void deletedViewingOrOwnerBlocksCommentsAndDeletedAuthorIsHidden() throws Exception {
        var fixture = fixture(true);
        long id = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "탈퇴 전 댓글", 201).path("id").longValue();
        jdbc.update("update app_users set deleted_at = ? where id = ?", Timestamp.from(NOW), fixture.friend().getId());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(get(commentPath(id)).with(as(fixture.owner()))).andExpect(status().isNotFound());
        jdbc.update("update user_game_logs set deleted_at = ? where user_id = ?", Timestamp.from(NOW), fixture.owner().getId());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner()))).andExpect(status().isNotFound());
        jdbc.update("update user_game_logs set deleted_at = null where user_id = ?", fixture.owner().getId());
        jdbc.update("update app_users set deleted_at = ? where id = ?", Timestamp.from(NOW), fixture.owner().getId());
        var third = user();
        mvc.perform(get(listPath(fixture.videoId())).with(as(third))).andExpect(status().isNotFound());
    }

    @Test
    void cursorIsStableAcrossDeletionAndBoundToVideo() throws Exception {
        var fixture = fixture(true);
        long first = create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "1", 201).path("id").longValue();
        long second = create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "2", 201).path("id").longValue();
        create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "3", 201);
        var response = mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(3))
                .andExpect(jsonPath("$.items[0].id").value(first)).andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        String cursor = mapper.readTree(response.getResponse().getContentAsString()).path("nextCursor").stringValue();
        mvc.perform(delete(commentPath(first)).with(as(fixture.owner()))).andExpect(status().isNoContent());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())).param("cursor", cursor).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasNext").value(false));
        videos.delete(fixture.videoId(), NOW);
        long newVideo = videos.insertPending(fixture.recordId(), "test/" + UUID.randomUUID(), "video/mp4", 1, NOW);
        videos.markReady(newVideo, NOW);
        mvc.perform(get(listPath(newVideo)).with(as(fixture.owner())).param("cursor", cursor)).andExpect(status().isBadRequest());
    }

    @Test
    void validatesInputAndRejectsReusedRequestWithDifferentContent() throws Exception {
        var fixture = fixture(true);
        String requestId = UUID.randomUUID().toString();
        create(fixture.friend(), fixture.videoId(), requestId, "첫 내용", 201);
        create(fixture.friend(), fixture.videoId(), requestId, "다른 내용", 409);
        create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), " ", 400);
        create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "가".repeat(1001), 400);
        create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "금지\0문자", 400);
        create(fixture.friend(), fixture.videoId(), "not-a-uuid", "댓글", 400);
        mvc.perform(post(listPath(fixture.videoId())).with(as(fixture.friend())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"댓글\"}")).andExpect(status().isBadRequest());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())).param("cursor", "garbage")).andExpect(status().isBadRequest());
        assertThat(notificationCount(fixture.owner())).isEqualTo(1);
    }

    @Test
    void disablingCommentPushStillStoresInboxNotification() throws Exception {
        var fixture = fixture(true);
        jdbc.update("insert into notification_settings(user_id,social_reaction_enabled,updated_at) values (?,false,?)",
                fixture.owner().getId(), Timestamp.from(NOW));
        create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "댓글", 201);
        assertThat(notificationCount(fixture.owner())).isEqualTo(1);
        assertThat(outboxCount(fixture.owner())).isZero();
    }

    private Fixture fixture(boolean friends) throws Exception {
        var owner = user();
        var friend = user();
        if (friends) {
            var relationship = Friendship.request(owner, friend, NOW);
            relationship.accept(NOW);
            friendships.saveAndFlush(relationship);
        }
        long gameId = ingest(game(TODAY, "FINISHED", 5, 3));
        viewing(owner, gameId, team("LG"), "STADIUM");
        var response = mvc.perform(post("/api/inning-records").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("gameId", gameId, "clientRecordId", UUID.randomUUID().toString(),
                        "inning", 1, "half", "TOP", "recordedAt", NOW.minusSeconds(30).toString(), "text", "댓글 테스트"))))
                .andExpect(status().isCreated()).andReturn();
        long recordId = mapper.readTree(response.getResponse().getContentAsString()).path("id").longValue();
        long videoId = videos.insertPending(recordId, "test/" + UUID.randomUUID(), "video/mp4", 10, NOW);
        videos.markReady(videoId, NOW);
        return new Fixture(owner, friend, recordId, videoId);
    }

    @Test
    void repliesUseExistingApiAndNotifyOnlyParentAuthorWithIdempotentRetry() throws Exception {
        var fixture = fixture(true);
        long parent = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "원댓글", 201).path("id").longValue();
        var third = user();
        var relationship = Friendship.request(fixture.owner(), third, NOW);
        relationship.accept(NOW);
        friendships.saveAndFlush(relationship);
        String clientId = UUID.randomUUID().toString();
        var reply = reply(third, fixture.videoId(), clientId, "  답글  ", parent, 201);
        long id = reply.path("id").longValue();
        assertThat(reply.path("parentCommentId").longValue()).isEqualTo(parent);
        assertThat(reply.path("content").stringValue()).isEqualTo("답글");
        assertThat(reply(third, fixture.videoId(), clientId, "답글", parent, 200).path("id").longValue()).isEqualTo(id);
        assertThat(notificationCount(fixture.owner())).isEqualTo(1);
        assertThat(notificationCount(fixture.friend())).isEqualTo(1);
        assertThat(outboxCount(fixture.friend())).isEqualTo(1);
        assertThat(notificationCount(third)).isZero();
        var data = mapper.readTree(jdbc.queryForObject("select data_json from notifications where user_id = ?", String.class, fixture.friend().getId()));
        assertThat(data.path("parentCommentId").stringValue()).isEqualTo(Long.toString(parent));
        assertThat(data.path("commentId").stringValue()).isEqualTo(Long.toString(id));
        assertThat(data.path("ownerUserId").stringValue()).isEqualTo(fixture.owner().getId().toString());
        assertThat(data.path("link").stringValue()).isEqualTo("https://inning-log.vercel.app/timeline/"
                + fixture.owner().getId() + "?gameId=" + data.path("gameId").stringValue()
                + "&recordId=" + fixture.recordId() + "&videoId=" + fixture.videoId() + "&commentId=" + id);
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items[0].parentCommentId").isEmpty()).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get(commentPath(id)).with(as(fixture.friend())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentCommentId").value(parent));
        mvc.perform(patch(commentPath(id)).with(as(third)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"답글 수정\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentCommentId").value(parent));
        assertThat(reply(third, fixture.videoId(), clientId, "답글", parent, 200).path("content").stringValue()).isEqualTo("답글 수정");
        reply(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "내 댓글에 답글", parent, 201);
        assertThat(notificationCount(fixture.friend())).isEqualTo(1);
    }

    @Test
    void rejectsCrossVideoNestedMissingAndInvalidParentsAndDifferentIdempotencyParent() throws Exception {
        var fixture = fixture(true);
        long first = create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "첫 댓글", 201).path("id").longValue();
        long second = create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "둘째 댓글", 201).path("id").longValue();
        String client = UUID.randomUUID().toString();
        long reply = reply(fixture.friend(), fixture.videoId(), client, "답글", first, 201).path("id").longValue();
        reply(fixture.friend(), fixture.videoId(), client, "답글", second, 409);
        create(fixture.friend(), fixture.videoId(), client, "답글", 409);
        reply(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "중첩", reply, 400);
        reply(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "없음", Long.MAX_VALUE, 404);
        reply(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "음수", -1, 400);
        reply(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "영", 0, 400);
        videos.delete(fixture.videoId(), NOW);
        long replacement = videos.insertPending(fixture.recordId(), "test/" + UUID.randomUUID(), "video/mp4", 10, NOW);
        videos.markReady(replacement, NOW);
        reply(fixture.friend(), replacement, UUID.randomUUID().toString(), "다른 영상", first, 404);
    }

    @Test
    void deletingRootRemovesRepliesAndCannotRecreateOrEditDeletedThread() throws Exception {
        var fixture = fixture(true);
        long parent = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "원댓글", 201).path("id").longValue();
        long reply = reply(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "답글", parent, 201).path("id").longValue();
        mvc.perform(delete(commentPath(parent)).with(as(fixture.friend()))).andExpect(status().isNoContent());
        mvc.perform(delete(commentPath(parent)).with(as(fixture.friend()))).andExpect(status().isNoContent());
        mvc.perform(get(commentPath(reply)).with(as(fixture.owner()))).andExpect(status().isNotFound());
        mvc.perform(patch(commentPath(reply)).with(as(fixture.owner())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"부활 불가\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        reply(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "삭제 후 답글", parent, 404);
        assertThat(jdbc.queryForObject("select content from video_comments where id = ?", String.class, reply)).isEqualTo("[삭제됨]");
    }

    @Test
    void deletingReplyPreservesParentAndOtherReplies() throws Exception {
        var fixture = fixture(true);
        long parent = create(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "원댓글", 201).path("id").longValue();
        String client = UUID.randomUUID().toString();
        long reply = reply(fixture.friend(), fixture.videoId(), client, "삭제 답글", parent, 201).path("id").longValue();
        reply(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "유지 답글", parent, 201);
        mvc.perform(delete(commentPath(reply)).with(as(fixture.owner()))).andExpect(status().isNotFound());
        mvc.perform(delete(commentPath(reply)).with(as(fixture.friend()))).andExpect(status().isNoContent());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(2));
        reply(fixture.friend(), fixture.videoId(), client, "삭제 답글", parent, 409);
    }

    @Test
    void deletedParentAuthorHidesThreadAndAccessLostParentAuthorReceivesNoNotification() throws Exception {
        var fixture = fixture(true);
        long parent = create(fixture.friend(), fixture.videoId(), UUID.randomUUID().toString(), "원댓글", 201).path("id").longValue();
        jdbc.update("delete from friendships where requester_id = ? and receiver_id = ?", fixture.owner().getId(), fixture.friend().getId());
        long reply = reply(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "답글", parent, 201).path("id").longValue();
        assertThat(notificationCount(fixture.friend())).isZero();
        jdbc.update("update app_users set deleted_at = ? where id = ?", Timestamp.from(NOW), fixture.friend().getId());
        mvc.perform(get(listPath(fixture.videoId())).with(as(fixture.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalCount").value(0));
        mvc.perform(get(commentPath(reply)).with(as(fixture.owner()))).andExpect(status().isNotFound());
        reply(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "답글", parent, 404);
    }

    @Test
    void explicitNullParentRetainsRootRequestAndReplyPreferenceStillStoresInbox() throws Exception {
        var fixture = fixture(true);
        String client = UUID.randomUUID().toString();
        var result = mvc.perform(post(listPath(fixture.videoId())).with(as(fixture.friend())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientCommentId\":\"" + client + "\",\"content\":\"원댓글\",\"parentCommentId\":null}"))
                .andExpect(status().isCreated()).andReturn();
        long parent = mapper.readTree(result.getResponse().getContentAsString()).path("id").longValue();
        assertThat(create(fixture.friend(), fixture.videoId(), client, "원댓글", 200).path("id").longValue()).isEqualTo(parent);
        jdbc.update("insert into notification_settings(user_id,social_reaction_enabled,updated_at) values (?,false,?)",
                fixture.friend().getId(), Timestamp.from(NOW));
        reply(fixture.owner(), fixture.videoId(), UUID.randomUUID().toString(), "답글", parent, 201);
        assertThat(notificationCount(fixture.friend())).isEqualTo(1);
        assertThat(outboxCount(fixture.friend())).isZero();
    }

    private JsonNode reply(User author, long videoId, String clientId, String content, long parent, int expected) throws Exception {
        var response = mvc.perform(post(listPath(videoId)).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("clientCommentId", clientId, "content", content, "parentCommentId", parent))))
                .andExpect(status().is(expected)).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private JsonNode create(User author, long videoId, String clientId, String content, int expected) throws Exception {
        var response = mvc.perform(post(listPath(videoId)).with(as(author)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("clientCommentId", clientId, "content", content))))
                .andExpect(status().is(expected)).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private long notificationCount(User user) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ? and notification_type = 'TIMELINE_COMMENT'", Long.class, user.getId());
    }
    private long outboxCount(User user) {
        return jdbc.queryForObject("select count(*) from notification_outbox where user_id = ? and notification_type = 'TIMELINE_COMMENT'", Long.class, user.getId());
    }
    private static String listPath(long videoId) { return "/api/videos/" + videoId + "/comments"; }
    private static String commentPath(long id) { return "/api/video-comments/" + id; }
    private record Fixture(User owner, User friend, long recordId, long videoId) {}
}
