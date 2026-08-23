package com.inninglog.global.firebase;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.firebase")
public record FirebaseProperties(boolean enabled, String projectId) {
}
