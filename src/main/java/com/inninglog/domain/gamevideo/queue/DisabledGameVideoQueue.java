package com.inninglog.domain.gamevideo.queue;

import com.inninglog.domain.game.exception.GameException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.game-videos", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledGameVideoQueue implements GameVideoQueue {
    @Override
    public void sendJob(String body) {
        throw new GameException(HttpStatus.SERVICE_UNAVAILABLE, "GAME_VIDEO_DISABLED", "경기 영상 만들기가 설정되지 않았습니다.");
    }

    @Override
    public List<Message> receiveResults(int max) { return List.of(); }

    @Override
    public void acknowledge(Message message) {}
}
