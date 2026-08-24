package com.inninglog.global.firebase;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.firebase")
public record FirebaseProperties(boolean enabled, String projectId) {

    public FirebaseProperties {
        if (enabled && (projectId == null || projectId.isBlank())) {
            throw new IllegalArgumentException(
                    "app.firebase.project-id must be configured when Firebase is enabled.");
        }
        if (projectId != null) {
            projectId = projectId.trim();
        }
    }
}
