package com.inninglog.domain.onboarding.service;

import com.inninglog.domain.onboarding.dto.OnboardingStatusResponse;
import com.inninglog.domain.onboarding.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.onboarding.exception.OnboardingAlreadyCompletedException;
import com.inninglog.domain.user.dto.UserResponse;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.DuplicateUsernameException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.user.service.UsernamePolicy;
import com.inninglog.domain.team.entity.KboTeam;
import com.inninglog.domain.team.service.TeamQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileSetupService {

    private final UserRepository userRepository;
    private final TeamQueryService teamQueryService;

    public ProfileSetupService(UserRepository userRepository, TeamQueryService teamQueryService) {
        this.userRepository = userRepository;
        this.teamQueryService = teamQueryService;
    }

    @Transactional
    public UserResponse setup(String subject, String username, String nickname) {
        User user = lockUser(subject);
        String normalizedUsername = UsernamePolicy.normalize(username);

        validateUsernameAvailable(user, normalizedUsername);

        user.setupProfile(normalizedUsername, nickname.trim());
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public OnboardingStatusResponse getOnboardingStatus(String subject) {
        return OnboardingStatusResponse.from(findUser(subject));
    }

    @Transactional
    public OnboardingStatusResponse setupUsername(String subject, String username) {
        User user = findOnboardingUser(subject);
        String normalizedUsername = UsernamePolicy.normalize(username);

        validateUsernameAvailable(user, normalizedUsername);
        user.setupOnboardingUsername(normalizedUsername);
        return OnboardingStatusResponse.from(user);
    }

    @Transactional
    public OnboardingStatusResponse setupNickname(String subject, String nickname) {
        User user = findOnboardingUser(subject);
        user.setupOnboardingNickname(nickname.trim());
        return OnboardingStatusResponse.from(user);
    }

    @Transactional
    public OnboardingStatusResponse completeOnboarding(String subject, Long favoriteTeamId) {
        User user = findOnboardingUser(subject);
        KboTeam favoriteTeam = teamQueryService.getEntityById(favoriteTeamId);

        user.selectInitialFavoriteTeam(favoriteTeam);
        return OnboardingStatusResponse.from(user);
    }

    @Transactional
    public UserResponse selectInitialFavoriteTeam(String subject, Long favoriteTeamId) {
        User user = lockUser(subject);
        KboTeam favoriteTeam = teamQueryService.getEntityById(favoriteTeamId);

        user.selectInitialFavoriteTeam(favoriteTeam);
        return UserResponse.from(user);
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

    private void validateUsernameAvailable(User user, String normalizedUsername) {
        userRepository.findByUsername(normalizedUsername)
                .filter(existingUser -> !existingUser.getId().equals(user.getId()))
                .ifPresent(existingUser -> {
                    throw new DuplicateUsernameException();
                });
    }

    private User findOnboardingUser(String subject) {
        User user = lockUser(subject);
        if (user.isOnboardingCompleted()) {
            throw new OnboardingAlreadyCompletedException();
        }
        return user;
    }

    private User findUser(String subject) {
        try {
            return userRepository.findByIdAndDeletedAtIsNull(Long.valueOf(subject))
                    .orElseThrow(UserNotFoundException::new);
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }

    private User lockUser(String subject) {
        try {
            User user = userRepository.findByIdForUpdate(Long.valueOf(subject))
                    .orElseThrow(UserNotFoundException::new);
            if (user.isDeleted()) {
                throw new AccountDeletedException();
            }
            return user;
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }
}
