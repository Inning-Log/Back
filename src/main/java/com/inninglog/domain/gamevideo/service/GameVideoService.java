package com.inninglog.domain.gamevideo.service;

import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.gamevideo.config.GameVideoProperties;
import com.inninglog.domain.gamevideo.dto.GameVideoFriendsResponse;
import com.inninglog.domain.gamevideo.dto.GameVideoResponse;
import com.inninglog.domain.gamevideo.model.GameVideo;
import com.inninglog.domain.gamevideo.model.GameVideoMode;
import com.inninglog.domain.gamevideo.model.GameVideoStatus;
import com.inninglog.domain.gamevideo.model.Participant;
import com.inninglog.domain.gamevideo.model.SegmentRender;
import com.inninglog.domain.gamevideo.model.SegmentStatus;
import com.inninglog.domain.gamevideo.repository.GameVideoRepository;
import com.inninglog.domain.gamevideo.repository.GameVideoSourceRepository;
import com.inninglog.domain.video.config.VideoProperties;
import com.inninglog.domain.video.storage.VideoStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "영상 저장하기": one shareable 9:16 video per user and game. Each inning is rendered as a segment by the worker
 * (cached by contents), then the segments are joined without re-encoding.
 */
@Service
public class GameVideoService {
    private final GameVideoRepository videos;
    private final GameVideoSourceRepository sources;
    private final GameRepository games;
    private final GameUserAccess access;
    private final SegmentPlanner planner;
    private final GameVideoJobs jobs;
    private final VideoStorage storage;
    private final GameVideoProperties properties;
    private final VideoProperties storageProperties;
    private final Clock clock;

    public GameVideoService(GameVideoRepository videos, GameVideoSourceRepository sources, GameRepository games,
                            GameUserAccess access, SegmentPlanner planner, GameVideoJobs jobs, VideoStorage storage,
                            GameVideoProperties properties, VideoProperties storageProperties, Clock clock) {
        this.videos = videos;
        this.sources = sources;
        this.games = games;
        this.access = access;
        this.planner = planner;
        this.jobs = jobs;
        this.storage = storage;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GameVideoFriendsResponse friends(String subject, long gameId) {
        var user = access.require(subject, false);
        requireGameLog(user.getId(), gameId);
        var friends = sources.friendsWithClips(user.getId(), gameId).stream().map(GameVideoFriendsResponse.Friend::from).toList();
        return new GameVideoFriendsResponse(properties.maxFriends(), friends);
    }

    @Transactional
    public GameVideoResponse request(String subject, long gameId, List<Long> friendIds) {
        if (!properties.enabled())
            throw new GameException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_VIDEO_DISABLED", "경기 영상 만들기가 설정되지 않았습니다.");
        // Locking the user serializes one user's requests, so at most one video is in progress.
        var user = access.require(subject, true);
        var game = games.find(gameId).orElseThrow(() -> GameException.notFound("GAME_NOT_FOUND"));
        Participant requester = requireGameLog(user.getId(), gameId);
        List<Participant> members = members(requester, gameId, friendIds == null ? List.of() : friendIds);

        var existing = videos.findActive(user.getId(), gameId).orElse(null);
        if (existing != null && existing.inProgress())
            throw GameException.conflict("GAME_VIDEO_IN_PROGRESS", "영상을 만들고 있어요. 완료된 뒤 다시 시도해 주세요.");

        var clips = sources.clips(gameId, members.stream().map(Participant::userId).toList());
        if (clips.isEmpty())
            throw GameException.conflict("NO_CLIPS", "영상으로 만들 기록이 없습니다.");
        var segments = planner.plan(game, members, clips, properties.templateVersion());

        Instant now = clock.instant();
        if (existing != null) discard(existing, now);
        String outputKey = properties.outputPrefix() + "/users/" + user.getId() + "/games/" + gameId + "/" + UUID.randomUUID() + ".mp4";
        long videoId = videos.insertVideo(user.getId(), gameId,
                members.size() == 1 ? GameVideoMode.SOLO : GameVideoMode.WITH_FRIENDS, outputKey, segments.size(), now);
        for (int i = 0; i < members.size(); i++) videos.insertMember(videoId, i, members.get(i).userId());
        for (int i = 0; i < segments.size(); i++) {
            var segment = segments.get(i);
            SegmentRender render = ensureRender(segment, now);
            videos.insertSegment(videoId, i, render.id(), segment.inning(), segment.half());
        }
        // Every inning may already be cached (e.g. after deleting and saving again).
        jobs.startMergeIfReady(videoId);
        return response(videos.find(videoId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public GameVideoResponse get(String subject, long gameId) {
        var user = access.require(subject, false);
        var video = videos.findActive(user.getId(), gameId).orElseThrow(() -> GameException.notFound("GAME_VIDEO_NOT_FOUND"));
        return response(video);
    }

    @Transactional
    public void delete(String subject, long gameId) {
        var user = access.require(subject, true);
        videos.findActive(user.getId(), gameId).ifPresent(video -> discard(video, clock.instant()));
    }

    /** Called on withdrawal. Rows are removed with the account; files are removed after commit. */
    @Transactional
    public void deleteForUser(long userId) {
        Instant now = clock.instant();
        videos.activeForUser(userId).forEach(video -> discard(video, now));
    }

    /** Reuses a cached segment when its file still exists, otherwise asks the worker to render it. */
    private SegmentRender ensureRender(SegmentPlanner.Segment segment, Instant now) {
        var existing = videos.lockRender(segment.renderKey()).orElse(null);
        if (existing == null) {
            String key = properties.segmentPrefix() + "/" + segment.renderKey() + ".mp4";
            videos.insertRender(segment.renderKey(), key, now);
            var render = videos.findRender(segment.renderKey()).orElseThrow();
            jobs.publishRender(render, segment.payload());
            return render;
        }
        boolean reusable = switch (existing.status()) {
            // Segment files expire through the bucket lifecycle rule, so check the file is still there.
            case READY -> storage.head(existing.objectKey()).isPresent();
            case PROCESSING -> existing.requestedAt().isAfter(now.minus(properties.segmentTimeout()));
            case FAILED -> false;
        };
        if (reusable) return existing;
        videos.restartRender(existing.id(), now);
        var render = videos.findRender(segment.renderKey()).orElseThrow();
        jobs.publishRender(render, segment.payload());
        return render;
    }

    private Participant requireGameLog(long userId, long gameId) {
        return sources.participants(gameId, List.of(userId)).stream().findFirst()
                .orElseThrow(() -> GameException.notFound("GAME_LOG_NOT_FOUND"));
    }

    private List<Participant> members(Participant requester, long gameId, List<Long> friendIds) {
        var ids = new LinkedHashSet<Long>(friendIds);
        if (ids.size() > properties.maxFriends())
            throw new GameException(HttpStatus.BAD_REQUEST, "TOO_MANY_FRIENDS",
                    "친구는 최대 " + properties.maxFriends() + "명까지 함께 넣을 수 있습니다.");
        List<Participant> result = new ArrayList<>();
        result.add(requester);
        if (ids.isEmpty()) return result;
        Map<Long, Participant> eligible = sources.friendsWithClips(requester.userId(), gameId).stream()
                .collect(Collectors.toMap(Participant::userId, Function.identity()));
        for (Long id : ids) {
            var friend = id == null ? null : eligible.get(id);
            if (friend == null)
                throw new GameException(HttpStatus.BAD_REQUEST, "INVALID_FRIEND", "함께 넣을 수 없는 친구가 있습니다.");
            result.add(friend);
        }
        return result;
    }

    private void discard(GameVideo video, Instant now) {
        videos.delete(video.id(), now);
        // A video still merging may write its file later; the merge result handler removes it then.
        if (video.status() == GameVideoStatus.READY) jobs.deleteObjectAfterCommit(video.objectKey());
    }

    private GameVideoResponse response(GameVideo video) {
        String url = null;
        Instant expiresAt = null;
        if (video.status() == GameVideoStatus.READY) {
            var download = storage.presignDownload(video.objectKey(), storageProperties.downloadUrlTtl());
            url = download.url();
            expiresAt = download.expiresAt();
        }
        int ready = video.status() == GameVideoStatus.READY ? video.segmentCount()
                : (int) videos.segments(video.id()).stream().filter(segment -> segment.status() == SegmentStatus.READY).count();
        return new GameVideoResponse(video.id(), video.gameId(), video.status(), video.mode(), videos.memberIds(video.id()),
                video.segmentCount(), ready, video.durationSeconds(), video.failureReason(), url, expiresAt,
                video.requestedAt(), video.completedAt());
    }
}
