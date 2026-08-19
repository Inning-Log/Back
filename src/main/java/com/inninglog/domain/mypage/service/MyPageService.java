package com.inninglog.domain.mypage.service;

import com.inninglog.domain.auth.service.AuthUserNotFoundException;
import com.inninglog.domain.auth.service.AccountDeletedException;
import com.inninglog.domain.auth.service.DuplicateUsernameException;
import com.inninglog.domain.auth.service.UsernamePolicy;
import com.inninglog.domain.mypage.dto.MyPageResponse;
import com.inninglog.domain.mypage.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.team.entity.KboTeam;
import com.inninglog.domain.team.service.TeamQueryService;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MyPageService {

    private final UserRepository userRepository;
    private final TeamQueryService teamQueryService;

    public MyPageService(UserRepository userRepository, TeamQueryService teamQueryService) {
        this.userRepository = userRepository;
        this.teamQueryService = teamQueryService;
    }

    @Transactional(readOnly = true)
    public MyPageResponse getMyPage(String subject) {
        return MyPageResponse.from(findUser(subject));
    }

    @Transactional(readOnly = true)
    public UsernameAvailabilityResponse checkUsernameAvailability(String subject, String username) {
        User user = findUser(subject);
        String normalizedUsername = UsernamePolicy.normalize(username);
        boolean available = userRepository.findByUsername(normalizedUsername)
                .map(existingUser -> existingUser.getId().equals(user.getId()))
                .orElse(true);

        return new UsernameAvailabilityResponse(normalizedUsername, available);
    }

    @Transactional
    public MyPageResponse updateProfile(String subject, String username, String nickname) {
        User user = lockUser(subject);
        String normalizedUsername = UsernamePolicy.normalize(username);

        userRepository.findByUsername(normalizedUsername)
                .filter(existingUser -> !existingUser.getId().equals(user.getId()))
                .ifPresent(existingUser -> {
                    throw new DuplicateUsernameException();
                });

        user.updateProfile(normalizedUsername, nickname.trim());
        return MyPageResponse.from(user);
    }

    @Transactional
    public MyPageResponse updateFavoriteTeam(String subject, Long favoriteTeamId) {
        User user = lockUser(subject);
        KboTeam favoriteTeam = teamQueryService.getEntityById(favoriteTeamId);

        user.updateFavoriteTeam(favoriteTeam);
        return MyPageResponse.from(user);
    }

    @Transactional
    public MyPageResponse updateProfileImage(String subject, String profileImageUrl) {
        User user = lockUser(subject);
        String normalizedUrl = profileImageUrl == null || profileImageUrl.isBlank()
                ? null
                : profileImageUrl.trim();

        user.updateProfileImage(normalizedUrl);
        return MyPageResponse.from(user);
    }

    private User findUser(String subject) {
        try {
            return userRepository.findByIdAndDeletedAtIsNull(Long.valueOf(subject))
                    .orElseThrow(AuthUserNotFoundException::new);
        } catch (NumberFormatException exception) {
            throw new AuthUserNotFoundException();
        }
    }

    private User lockUser(String subject) {
        try {
            User user = userRepository.findByIdForUpdate(Long.valueOf(subject))
                    .orElseThrow(AuthUserNotFoundException::new);
            if (user.isDeleted()) {
                throw new AccountDeletedException();
            }
            return user;
        } catch (NumberFormatException exception) {
            throw new AuthUserNotFoundException();
        }
    }
}
