package com.inninglog.domain.auth.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.oauth.google")
public record GoogleOAuthProperties(
        String clientId,
        String jwkSetUri,
        List<String> issuers
) {

    public GoogleOAuthProperties {
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs";
        }
        if (issuers == null || issuers.isEmpty()) {
            issuers = List.of("https://accounts.google.com", "accounts.google.com");
        }
    }

    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }
}
