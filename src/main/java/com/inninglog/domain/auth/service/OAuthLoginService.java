package com.inninglog.domain.auth.service;

import com.inninglog.domain.auth.dto.LoginResponse;
import com.inninglog.domain.auth.dto.UserResponse;
import com.inninglog.domain.auth.entity.AuthProvider;
import com.inninglog.domain.auth.entity.OAuthAccount;
import com.inninglog.domain.auth.repository.OAuthAccountRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthLoginService {

    private final GoogleIdentityTokenVerifier googleIdentityTokenVerifier;
    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final AuthSessionService authSessionService;

    public OAuthLoginService(
            GoogleIdentityTokenVerifier googleIdentityTokenVerifier,
            UserRepository userRepository,
            OAuthAccountRepository oAuthAccountRepository,
            AuthSessionService authSessionService
    ) {
        this.googleIdentityTokenVerifier = googleIdentityTokenVerifier;
        this.userRepository = userRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.authSessionService = authSessionService;
    }

    @Transactional
    public LoginResponse loginWithGoogle(String credential) {
        GoogleUserInfo googleUserInfo = googleIdentityTokenVerifier.verify(credential);

        LoginResult loginResult = oAuthAccountRepository
                .findByProviderAndProviderUserId(AuthProvider.GOOGLE, googleUserInfo.subject())
                .map(account -> loginExistingUser(account, googleUserInfo))
                .orElseGet(() -> registerGoogleUser(googleUserInfo));

        AuthSessionService.SessionTokens tokens = authSessionService.createSession(loginResult.user());

        return LoginResponse.of(tokens, loginResult.isNewUser(), UserResponse.from(loginResult.user()));
    }

    private LoginResult loginExistingUser(OAuthAccount account, GoogleUserInfo googleUserInfo) {
        User user = userRepository.findByIdForUpdate(account.getUser().getId())
                .orElseThrow(AuthUserNotFoundException::new);
        if (user.isDeleted()) {
            throw new AccountDeletedException();
        }
        user.updateGoogleProfile(googleUserInfo.email(), googleUserInfo.picture());
        account.updateEmail(googleUserInfo.email());
        return new LoginResult(user, false);
    }

    private LoginResult registerGoogleUser(GoogleUserInfo googleUserInfo) {
        User user = userRepository.save(new User(
                googleUserInfo.email(),
                googleUserInfo.picture()));

        oAuthAccountRepository.save(new OAuthAccount(
                user,
                AuthProvider.GOOGLE,
                googleUserInfo.subject(),
                googleUserInfo.email()));

        return new LoginResult(user, true);
    }

    private record LoginResult(User user, boolean isNewUser) {
    }
}
