package com.inninglog.domain.notification.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Links use the routes published by the web client. An unset origin omits links. */
@Component
public class NotificationLinkService {
    private final String origin;

    public NotificationLinkService(@Value("${app.notifications.frontend-origin:}") String origin) {
        this.origin = origin == null ? "" : origin.strip().replaceAll("/+$", "");
        if (!this.origin.isEmpty()) {
            URI uri = URI.create(this.origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("Notification frontend origin must be an HTTPS origin.");
            }
        }
    }

    public String game(Long gameId) {
        // These events belong to today's game. The history route hides the client's record-create action.
        return url("/timeline?gameId=" + gameId);
    }

    public String video(Long gameId, Long recordId, Long videoId, Long commentId) {
        // Preserve the owner's history route; the numeric user route is friend mode in the client.
        return url("/timeline/history/" + gameId + "?recordId=" + recordId
                + "&videoId=" + videoId + "&commentId=" + commentId);
    }

    public String video(Long gameId, Long recordId, Long videoId, Long commentId,
                        long ownerUserId, long recipientUserId) {
        if (ownerUserId == recipientUserId) return video(gameId, recordId, videoId, commentId);
        return url("/timeline/" + ownerUserId + "?gameId=" + gameId + "&recordId=" + recordId
                + "&videoId=" + videoId + "&commentId=" + commentId);
    }

    public Map<String, String> friendshipData(Map<String, String> data, boolean accepted) {
        Map<String, String> result = new LinkedHashMap<>(data);
        String link = url(accepted ? "/mypage/friends" : "/home/notifications?tab=friends");
        if (link != null) result.put("link", link);
        return result;
    }

    private String url(String path) {
        return origin.isEmpty() ? null : origin + path;
    }
}
