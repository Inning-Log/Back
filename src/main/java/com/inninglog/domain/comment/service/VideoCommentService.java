package com.inninglog.domain.comment.service;

import com.inninglog.domain.comment.dto.*;
import com.inninglog.domain.comment.repository.VideoCommentRepository;
import com.inninglog.domain.comment.repository.VideoCommentRepository.Comment;
import com.inninglog.domain.comment.repository.VideoCommentRepository.Video;
import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.service.NotificationLinkService;
import com.inninglog.domain.notification.service.NotificationQueueService;
import com.inninglog.domain.notification.service.PushNotification;
import com.inninglog.domain.timeline.repository.TimelineRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoCommentService {
    private final VideoCommentRepository comments;
    private final GameUserAccess access;
    private final TimelineRepository timelines;
    private final NotificationQueueService notifications;
    private final NotificationLinkService links;
    private final Clock clock;

    public VideoCommentService(VideoCommentRepository comments, GameUserAccess access, TimelineRepository timelines,
                               NotificationQueueService notifications, NotificationLinkService links, Clock clock) {
        this.comments = comments; this.access = access; this.timelines = timelines;
        this.notifications = notifications; this.links = links; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentPageResponse list(String subject, long videoId, String cursor, int size) {
        long viewer = access.require(subject, false).getId();
        requireVisible(viewer, videoId);
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be between 1 and 100");
        var found = comments.page(videoId, decodeCursor(cursor, videoId), size + 1);
        boolean hasNext = found.size() > size;
        var page = found.subList(0, Math.min(found.size(), size));
        String next = hasNext ? encodeCursor(videoId, page.getLast().id()) : null;
        return new CommentPageResponse(page.stream().map(row -> row.response(viewer)).toList(),
                comments.count(videoId), next, hasNext);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CommentResponse get(String subject, long id) {
        long viewer = access.require(subject, false).getId();
        var comment = requireComment(id, false);
        requireVisible(viewer, comment.videoId());
        return comment.response(viewer);
    }

    @Transactional
    public Created create(String subject, long videoId, CommentCreateRequest request) {
        long authorId = access.require(subject, false).getId();
        var video = lockAndRequireVisible(subject, authorId, videoId, request.parentCommentId());
        Comment parent = request.parentCommentId() == null ? null : requireReplyParent(request.parentCommentId(), videoId);
        String content = normalize(request.content());
        String fingerprint = fingerprint(content);
        var existing = comments.findByClient(videoId, authorId, request.clientCommentId()).orElse(null);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)
                    || !java.util.Objects.equals(existing.parentCommentId(), request.parentCommentId()))
                throw GameException.conflict("COMMENT_REQUEST_CONFLICT", "같은 요청 식별자에 다른 댓글을 사용할 수 없습니다.");
            if (existing.deletedAt() != null)
                throw GameException.conflict("COMMENT_ALREADY_DELETED", "이미 삭제한 댓글 요청입니다.");
            return new Created(existing.response(authorId), false);
        }
        long id = comments.insert(videoId, authorId, request.clientCommentId(), fingerprint, content,
                request.parentCommentId(), clock.instant());
        var created = requireComment(id, false);
        long recipient = parent == null ? video.ownerId() : parent.authorId();
        if (authorId != recipient && timelines.visibleOwner(recipient, video.ownerId()).isPresent())
            notifyRecipient(video, created, recipient);
        return new Created(created.response(authorId), true);
    }

    @Transactional
    public CommentResponse update(String subject, long id, CommentUpdateRequest request) {
        long viewer = access.require(subject, false).getId();
        var before = requireComment(id, false);
        if (before.authorId() != viewer) throw GameException.notFound("COMMENT_NOT_FOUND");
        lockAndRequireVisible(subject, viewer, before.videoId());
        requireComment(id, false);
        comments.update(id, normalize(request.content()), clock.instant());
        return requireComment(id, false).response(viewer);
    }

    @Transactional
    public void delete(String subject, long id) {
        long viewer = access.require(subject, false).getId();
        var before = requireComment(id, true);
        if (before.authorId() != viewer) throw GameException.notFound("COMMENT_NOT_FOUND");
        lockAndRequireVisible(subject, viewer, before.videoId());
        comments.delete(id, clock.instant());
    }

    private Video lockAndRequireVisible(String subject, long viewer, long videoId) {
        return lockAndRequireVisible(subject, viewer, videoId, null);
    }

    private Video lockAndRequireVisible(String subject, long viewer, long videoId, Long parentId) {
        Video initial = requireVisible(viewer, videoId);
        if (parentId == null) comments.lockParents(viewer, initial);
        else comments.lockParents(viewer, initial, requireReplyParent(parentId, videoId).authorId());
        // Re-read after waiting for locks: deletion, unfriend and upload replacement must win if committed first.
        // JDBC bypasses a JPA user entity cached before an account-deletion transaction committed.
        if (!comments.activeUser(viewer)) throw GameException.notFound("USER_NOT_FOUND");
        return requireVisible(viewer, videoId);
    }

    private Video requireVisible(long viewer, long videoId) {
        Video video = comments.video(videoId).orElseThrow(() -> GameException.notFound("VIDEO_NOT_FOUND"));
        timelines.visibleOwner(viewer, video.ownerId()).orElseThrow(() -> GameException.notFound("VIDEO_NOT_FOUND"));
        return video;
    }

    private Comment requireComment(long id, boolean includeDeleted) {
        return comments.find(id).filter(c -> includeDeleted || (c.deletedAt() == null &&
                        (c.parentCommentId() == null || comments.find(c.parentCommentId())
                                .filter(p -> p.deletedAt() == null).isPresent())))
                .orElseThrow(() -> GameException.notFound("COMMENT_NOT_FOUND"));
    }

    private Comment requireReplyParent(long parentId, long videoId) {
        if (parentId <= 0) throw new IllegalArgumentException("parentCommentId는 양수여야 합니다.");
        Comment parent = requireComment(parentId, false);
        if (parent.videoId() != videoId) throw GameException.notFound("COMMENT_NOT_FOUND");
        if (parent.parentCommentId() != null)
            throw new GameException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "COMMENT_REPLY_DEPTH_EXCEEDED", "답글에는 답글을 작성할 수 없습니다.");
        return parent;
    }

    private void notifyRecipient(Video video, Comment comment, long recipient) {
        var data = new LinkedHashMap<String, String>();
        data.put("screen", "VIDEO_COMMENTS");
        data.put("videoId", Long.toString(video.id()));
        data.put("recordId", Long.toString(video.recordId()));
        data.put("gameId", Long.toString(video.gameId()));
        data.put("ownerUserId", Long.toString(video.ownerId()));
        data.put("commentId", Long.toString(comment.id()));
        data.put("actorUserId", Long.toString(comment.authorId()));
        boolean reply = comment.parentCommentId() != null;
        if (reply) data.put("parentCommentId", Long.toString(comment.parentCommentId()));
        String link = links.video(video.gameId(), video.recordId(), video.id(), comment.id(), video.ownerId(), recipient);
        if (link != null) data.put("link", link);
        // Do not copy private comment text into push history or lock-screen previews.
        notifications.enqueueToUser("video-comment:" + comment.id() + ":created", recipient,
                new PushNotification(NotificationType.TIMELINE_COMMENT,
                        reply ? "댓글에 새 답글이 달렸어요" : "영상에 새 댓글이 달렸어요",
                        comment.author().nickname() + (reply ? "님이 회원님의 댓글에 답글을 남겼습니다."
                                : "님이 회원님의 영상에 댓글을 남겼습니다."), data));
    }

    private static String normalize(String content) {
        if (content == null || content.isBlank() || content.length() > 1000 || content.indexOf('\0') >= 0)
            throw new IllegalArgumentException("댓글은 1~1000자여야 합니다.");
        return content.strip();
    }

    private static String fingerprint(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static long decodeCursor(String cursor, long videoId) {
        if (cursor == null) return 0;
        try {
            if (cursor.length() > 128) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
            if (parts.length != 3 || !parts[0].equals("1") || Long.parseLong(parts[1]) != videoId)
                throw new IllegalArgumentException();
            long id = Long.parseLong(parts[2]);
            if (id < 1) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("잘못된 댓글 커서입니다."); }
    }

    private static String encodeCursor(long videoId, long id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" + videoId + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public record Created(CommentResponse comment, boolean created) {}
}
