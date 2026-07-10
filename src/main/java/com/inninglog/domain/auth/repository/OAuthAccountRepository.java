package com.inninglog.domain.auth.repository;

import java.util.Optional;

import com.inninglog.domain.auth.entity.AuthProvider;
import com.inninglog.domain.auth.entity.OAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
