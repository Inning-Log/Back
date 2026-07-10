package com.inninglog.domain.auth.service;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.NonNull;

@Service
public class GoogleJwtIdentityTokenVerifier implements GoogleIdentityTokenVerifier {

    private final GoogleOAuthProperties properties;
    private final JwtDecoder jwtDecoder;

    public GoogleJwtIdentityTokenVerifier(GoogleOAuthProperties properties) {
        this.properties = properties;
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(new GoogleIdTokenValidator(properties));
        this.jwtDecoder = decoder;
    }

    @Override
    public GoogleUserInfo verify(String credential) {
        if (!properties.configured()) {
            throw new IllegalStateException("GOOGLE_CLIENT_ID is required for Google login.");
        }

        try {
            Jwt jwt = jwtDecoder.decode(credential);
            Boolean emailVerified = jwt.getClaim("email_verified");
            if (!Boolean.TRUE.equals(emailVerified)) {
                throw new BadJwtException("Google email is not verified.");
            }

            return new GoogleUserInfo(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("name"),
                    jwt.getClaimAsString("picture"));
        } catch (JwtException exception) {
            throw new BadJwtException("Invalid Google ID token.", exception);
        }
    }

    private static class GoogleIdTokenValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error INVALID_TOKEN = new OAuth2Error("invalid_token");

        private final GoogleOAuthProperties properties;
        private final OAuth2TokenValidator<Jwt> defaultValidator = JwtValidators.createDefault();

        GoogleIdTokenValidator(GoogleOAuthProperties properties) {
            this.properties = properties;
        }

        @Override
        public OAuth2TokenValidatorResult validate(@NonNull Jwt token) {
            OAuth2TokenValidatorResult defaultResult = defaultValidator.validate(token);
            if (defaultResult.hasErrors()) {
                return defaultResult;
            }

            List<String> audience = token.getAudience();
            if (audience == null || !audience.contains(properties.clientId())) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }

            String issuer = token.getIssuer() != null ? token.getIssuer().toString() : null;
            if (!properties.issuers().contains(issuer)) {
                return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
            }

            return OAuth2TokenValidatorResult.success();
        }
    }
}
