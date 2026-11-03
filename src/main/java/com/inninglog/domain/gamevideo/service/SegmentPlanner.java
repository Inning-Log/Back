package com.inninglog.domain.gamevideo.service;

import com.inninglog.domain.game.model.Game;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.gamevideo.model.ClipSource;
import com.inninglog.domain.gamevideo.model.Participant;
import com.inninglog.domain.timeline.model.InningHalf;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Splits a game into inning segments and describes everything drawn in each one.
 * The description doubles as the cache key: an unchanged inning yields the same render key.
 */
@Component
public class SegmentPlanner {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd (E) HH:mm", Locale.KOREAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper mapper;

    public SegmentPlanner(ObjectMapper mapper) { this.mapper = mapper; }

    public record Segment(int inning, InningHalf half, String renderKey, Map<String, Object> payload) {}

    /**
     * @param members participants in card order; the first is the requester
     * @param templateVersion bumped whenever the worker draws differently
     */
    public List<Segment> plan(Game game, List<Participant> members, List<ClipSource> clips, String templateVersion) {
        // inning, then TOP, unknown half, BOTTOM
        var groups = new TreeMap<Integer, List<ClipSource>>();
        for (ClipSource clip : clips) groups.computeIfAbsent(clip.inning() * 3 + halfOrder(clip.half()), key -> new ArrayList<>()).add(clip);

        Map<String, Object> header = header(game);
        Participant requester = members.getFirst();
        Map<String, Object> footer = ordered("handle", nullToEmpty(requester.username()), "team", requester.teamName());
        boolean solo = members.size() == 1;
        List<Segment> segments = new ArrayList<>();
        for (var group : groups.values()) {
            group.sort(Comparator.comparing(ClipSource::recordedAt).thenComparing(ClipSource::recordId));
            List<Map<String, Object>> cards = new ArrayList<>();
            for (Participant member : members) {
                List<Map<String, Object>> own = group.stream().filter(clip -> clip.userId() == member.userId())
                        .map(this::clip).toList();
                cards.add(ordered("userId", member.userId(), "name", member.displayName(), "team", member.teamName(),
                        "clips", own));
            }
            ClipSource first = group.getFirst();
            Map<String, Object> payload = ordered("templateVersion", templateVersion, "header", header,
                    "inning", first.inning(), "half", first.half() == null ? null : first.half().name(),
                    "solo", solo, "footer", footer, "members", cards);
            segments.add(new Segment(first.inning(), first.half(), renderKey(payload), payload));
        }
        return segments;
    }

    private Map<String, Object> clip(ClipSource clip) {
        String score = clip.awayScore() == null || clip.homeScore() == null ? "" : clip.awayScore() + " : " + clip.homeScore();
        return ordered("sourceKey", clip.objectKey(), "time", CLOCK.format(clip.recordedAt().atZone(GameUserAccess.KST)),
                "caption", nullToEmpty(clip.caption()), "score", score);
    }

    private static Map<String, Object> header(Game game) {
        Instant at = game.scheduledAt() != null ? game.scheduledAt() : game.startedAt();
        String when = at != null ? DATE_TIME.format(at.atZone(GameUserAccess.KST)) : DATE.format(game.gameDate());
        return ordered("awayTeamCode", game.awayTeam().teamCode(), "homeTeamCode", game.homeTeam().teamCode(),
                "awayScore", game.awayScore() == null ? "-" : game.awayScore().toString(),
                "homeScore", game.homeScore() == null ? "-" : game.homeScore().toString(),
                "datetime", when, "stadium", nullToEmpty(game.stadiumName()));
    }

    private String renderKey(Map<String, Object> payload) {
        try {
            byte[] json = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int halfOrder(InningHalf half) {
        if (half == InningHalf.TOP) return 0;
        return half == null ? 1 : 2;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static Map<String, Object> ordered(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
