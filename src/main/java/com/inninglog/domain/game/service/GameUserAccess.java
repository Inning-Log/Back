package com.inninglog.domain.game.service;

import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class GameUserAccess {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final UserRepository users;
    public GameUserAccess(UserRepository users) { this.users = users; }

    public User require(String subject, boolean lock) {
        long id;
        try { id = Long.parseLong(subject); }
        catch (NumberFormatException exception) { throw GameException.notFound("USER_NOT_FOUND"); }
        User user = (lock ? users.findByIdForUpdate(id) : users.findByIdAndDeletedAtIsNull(id))
                .orElseThrow(() -> GameException.notFound("USER_NOT_FOUND"));
        if (user.isDeleted()) throw GameException.conflict("ACCOUNT_DELETED", "탈퇴한 계정입니다.");
        if (!user.isOnboardingCompleted() || user.getFavoriteTeam() == null)
            throw GameException.conflict("PROFILE_SETUP_REQUIRED", "온보딩을 완료해 주세요.");
        return user;
    }
}
