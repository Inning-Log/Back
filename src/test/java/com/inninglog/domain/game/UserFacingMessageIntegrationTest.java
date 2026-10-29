package com.inninglog.domain.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class UserFacingMessageIntegrationTest extends GameIntegrationSupport {
    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "invalid"})
    void invalidNotificationPaginationReturnsReadableMessage(String size) throws Exception {
        mvc.perform(get("/api/notifications").with(as(user())).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 형식과 필수 값을 확인해 주세요."));
    }

    @Test
    void missingNotificationAndEmptySettingsKeepTheirCodesAndExplainTheProblem() throws Exception {
        var viewer = user();
        mvc.perform(patch("/api/notifications/{id}/read", Long.MAX_VALUE).with(as(viewer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("알림을 찾을 수 없습니다."));
        mvc.perform(patch("/api/notification-settings").with(as(viewer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SETTINGS"))
                .andExpect(jsonPath("$.message").value("변경할 알림 설정을 하나 이상 선택해 주세요."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"receiverId\":-1}", "{\"receiverId\":\"invalid\"}"})
    void invalidFriendRequestsReturnReadableMessage(String body) throws Exception {
        mvc.perform(post("/api/friendships/requests").with(as(user()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 형식과 필수 값을 확인해 주세요."));
    }

    @Test
    void selfFriendRequestKeepsConflictContractWithReadableMessage() throws Exception {
        var viewer = user();
        mvc.perform(post("/api/friendships/requests").with(as(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":" + viewer.getId() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_FRIEND_REQUEST_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("자신에게는 친구 신청을 보낼 수 없습니다."));
    }
}
