package com.inninglog.global.firebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FirebasePropertiesTest {

    @Test
    void enabledFirebaseRequiresAnExplicitProjectId() {
        assertThatThrownBy(() -> new FirebaseProperties(true, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-id");
    }

    @Test
    void projectIdIsNormalized() {
        assertThat(new FirebaseProperties(true, " inning-log ").projectId())
                .isEqualTo("inning-log");
    }
}
