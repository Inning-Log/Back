package com.inninglog.global.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenProvider {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(String subject, Collection<String> roles) {
        return issue(subject, roles, null, null, true);
    }

    public IssuedToken issueSession(String subject, Collection<String> roles, Long sessionId, int generation) {
        return issue(subject, roles, sessionId, generation, false);
    }

    private IssuedToken issue(
            String subject,
            Collection<String> roles,
            Long sessionId,
            Integer generation,
            boolean developmentToken
    ) {
        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(properties.accessTokenExpiration());

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(subject)
                .claim("roles", roles);

        if (developmentToken) {
            claims.claim("dev", true);
        } else {
            claims.claim("sid", sessionId);
            claims.claim("sessionGeneration", generation);
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken("Bearer", accessToken, expiresAt);
    }

    public record IssuedToken(String tokenType, String accessToken, Instant expiresAt) {
    }
}
