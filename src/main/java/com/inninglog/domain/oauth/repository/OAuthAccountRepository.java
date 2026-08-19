package com.inninglog.domain.oauth.repository;

import java.util.Optional;
import java.util.List;

import com.inninglog.domain.oauth.entity.AuthProvider;
import com.inninglog.domain.oauth.entity.OAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

    List<OAuthAccount> findAllByUserId(Long userId);
}
