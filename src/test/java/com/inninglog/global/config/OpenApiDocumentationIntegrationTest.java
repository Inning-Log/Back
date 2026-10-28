package com.inninglog.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.List;
import java.util.Map;
import com.inninglog.domain.auth.dto.CurrentUserResponse;
import com.inninglog.domain.comment.dto.CommentResponse;
import com.inninglog.domain.comment.dto.CommentPageResponse;
import com.inninglog.domain.friendship.dto.FriendUserResponse;
import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.mypage.dto.MyPageResponse;
import com.inninglog.domain.notification.dto.NotificationItemResponse;
import com.inninglog.domain.notification.dto.NotificationListResponse;
import com.inninglog.domain.user.dto.UserResponse;
import com.inninglog.domain.video.dto.VideoResponse;
import com.inninglog.domain.video.model.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private tools.jackson.databind.ObjectMapper responseMapper;

    @Test
    void generatedSchemasAcceptActualNullResponseFields() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        List<Object> responses = List.of(
                new UserResponse(1L, null, "test@example.com", null, null, null, false),
                new MyPageResponse(1L, null, null, "test@example.com", null, null),
                new CurrentUserResponse("1", List.of("ROLE_USER"), null),
                new FriendUserResponse(1L, "tester", "Tester", null, null),
                new VideoResponse(1, 2, VideoStatus.PENDING, "video/mp4", 100, null, null, null),
                new CommentResponse(1, 2, new CommentResponse.Author(3, "tester", "Tester", null),
                        "comment", java.time.Instant.EPOCH, java.time.Instant.EPOCH, false, null),
                new CommentPageResponse(List.of(), 0, null, false),
                new NotificationItemResponse(1L,
                        com.inninglog.domain.notification.entity.NotificationType.FRIEND_REQUEST,
                        "title", "body", Map.of(), null, java.time.Instant.EPOCH),
                new NotificationListResponse(List.of(), null, false, 0),
                new GameResponse.Score(null, null));
        for (Object response : responses) {
            JsonNode json = objectMapper.readTree(responseMapper.writeValueAsString(response));
            JsonNode properties = schemas.path(response.getClass().getSimpleName()).path("properties");
            assertThat(properties.isMissingNode()).as(response.getClass().getSimpleName()).isFalse();
            for (var field : json.properties()) {
                if (field.getValue().isNull()) {
                    assertThat(allowsNull(properties.path(field.getKey()), schemas))
                            .as(response.getClass().getSimpleName() + "." + field.getKey() + " accepts actual null")
                            .isTrue();
                }
            }
        }

        Map<String, List<String>> nullableFields = Map.ofEntries(
                Map.entry("GameResponse", List.of("gameSequence", "scheduledAt", "stadiumName", "displayTeam", "opponentTeam", "myViewing", "resultObservedAt")),
                Map.entry("InningRecordResponse", List.of("half", "homeScore", "awayScore", "scoreObservedAt")),
                Map.entry("CreateInningRecordRequest", List.of("half")),
                Map.entry("TimelineResponse", List.of("viewing", "currentInning", "currentHalf", "nextCursor")),
                Map.entry("TimelineProfilesResponse", List.of("nextAfterUserId")),
                Map.entry("State", List.of("inning", "half", "homeScore", "awayScore")),
                Map.entry("WinRateResponse", List.of("winRatePercent")),
                Map.entry("CommentCreateRequest", List.of("parentCommentId")),
                Map.entry("TeamSummaryResponse", List.of("logoUrl", "primaryColor")));
        nullableFields.forEach((model, fields) -> fields.forEach(field ->
                assertThat(allowsNull(schemas.path(model).path("properties").path(field), schemas))
                        .as(model + "." + field).isTrue()));
    }

    @Test
    void nullableObjectReferencesAllowBothObjectsAndNullWithoutChangingSharedModels() throws Exception {
        JsonNode schemas = document().path("components").path("schemas");
        for (String model : List.of("FriendUserResponse", "MyPageResponse")) {
            JsonNode favoriteTeam = schemas.path(model).path("properties").path("favoriteTeam");
            assertThat(favoriteTeam.has("$ref")).isFalse();
            assertThat(favoriteTeam.has("type")).isFalse();
            assertThat(favoriteTeam.path("anyOf").get(0).path("$ref").asText())
                    .isEqualTo("#/components/schemas/TeamSummaryResponse");
            assertThat(allowsNull(favoriteTeam, schemas)).isTrue();
        }
        assertThat(allowsNull(schemas.path("TeamSummaryResponse"), schemas)).isFalse();
        assertThat(allowsNull(schemas.path("LoginResponse").path("properties").path("user"), schemas)).isFalse();
        rejectImpossibleNullableReferences(schemas);
    }

    @Test
    void commentReplyAndNotificationResponseContractsAreExplicit() throws Exception {
        JsonNode paths = document().path("paths");
        JsonNode create = paths.path("/api/videos/{videoId}/comments").path("post");
        assertThat(create.path("description").asText()).contains("1계층", "같은 UUID", "부모 댓글 작성자");
        for (String code : List.of("200", "201", "400", "401", "404", "409")) {
            assertThat(create.path("responses").has(code)).as("comment POST " + code).isTrue();
        }
        assertThat(create.path("responses").path("400").path("description").asText())
                .contains("COMMENT_REPLY_DEPTH_EXCEEDED");
        for (String code : List.of("200", "201")) {
            assertThat(create.path("responses").path(code).path("content").path("application/json")
                    .path("schema").path("$ref").asText()).endsWith("/CommentResponse");
        }
        for (String path : List.of("/api/notifications/{id}/read", "/api/notifications/read-all")) {
            JsonNode responses = paths.path(path).path("patch").path("responses");
            assertThat(responses.has("204")).isTrue();
            assertThat(responses.path("204").has("content")).isFalse();
            assertThat(responses.has("200")).isFalse();
        }
    }

    @Test
    void timelineResetAndNotificationRecipientContractsArePublished() throws Exception {
        JsonNode doc = document();
        JsonNode schemas = doc.path("components").path("schemas");
        for (String name : List.of("TimelineResponse", "TimelineProfilesResponse")) {
            JsonNode properties = schemas.path(name).path("properties");
            assertThat(properties.path("today").path("description").asText()).contains("KST", "달력");
            assertThat(properties.path("timelineDate").path("format").asText()).isEqualTo("date");
            assertThat(properties.path("nextResetAt").path("format").asText()).isEqualTo("date-time");
        }
        assertThat(schemas.path("NotificationItemResponse").path("properties").path("data")
                .path("description").asText()).contains("audienceUserId", "recipientUserId", "ownerUserId", "actorUserId", "HTTPS");
        assertThat(doc.path("paths").path("/api/timelines/profiles").path("get").path("description").asText())
                .contains("timelineDate", "nextResetAt", "전날", "games");
        assertThat(doc.path("paths").path("/api/inning-records").path("post").path("description").asText())
                .contains("당일 첫 경기 시작 전", "리셋 후에도 기존 기록", "작성 가능 시간은 그대로");
        assertThat(schemas.path("TimelineResponse").path("properties").path("nextResetAt").path("description").asText())
                .contains("30분 전");
        assertThat(allowsNull(schemas.path("WinRateResponse").path("properties").path("postseason"), schemas)).isTrue();
        assertThat(allowsNull(schemas.path("PostseasonWinRate").path("properties").path("winRatePercent"), schemas)).isTrue();
    }

    private JsonNode document() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static boolean allowsNull(JsonNode schema, JsonNode schemas) {
        if (schema.isMissingNode()) return false;
        if (schema.has("enum")) {
            boolean nullOption = false;
            for (JsonNode value : schema.path("enum")) nullOption |= value.isNull();
            if (!nullOption) return false;
        }
        if (schema.has("$ref") && !allowsNull(schemas.path(schema.path("$ref").asText()
                .replace("#/components/schemas/", "")), schemas)) return false;
        for (String composition : List.of("anyOf", "oneOf")) {
            if (schema.has(composition)) {
                boolean accepts = false;
                for (JsonNode branch : schema.path(composition)) accepts |= allowsNull(branch, schemas);
                if (!accepts) return false;
                if (!schema.has("type")) return true;
            }
        }
        JsonNode type = schema.path("type");
        if (type.isTextual()) return type.asText().equals("null");
        if (type.isArray()) for (JsonNode value : type) if (value.asText().equals("null")) return true;
        return false;
    }

    private static void rejectImpossibleNullableReferences(JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            assertThat(node.path("type").toString()).doesNotContain("null");
        }
        node.forEach(OpenApiDocumentationIntegrationTest::rejectImpossibleNullableReferences);
    }

    @Test
    void authOnboardingAndMyPageExposeKoreanSuccessAndErrorDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.summary")
                        .value("Google ID 토큰 로그인"))
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['503']").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.refreshToken").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.expiresAt").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.refreshTokenExpiresAt").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.summary")
                        .value("서비스 토큰 갱신"))
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['401']").exists())
                .andExpect(jsonPath("$.components.schemas.TokenPairResponse.properties.accessTokenExpiresAt")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.summary")
                        .value("현재 세션 로그아웃"))
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout-all'].post.summary")
                        .value("모든 세션 로그아웃"))
                .andExpect(jsonPath("$.paths['/api/auth/logout-all'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].delete.summary")
                        .value("회원 탈퇴"))
                .andExpect(jsonPath("$.paths['/api/users/me'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].delete.responses['401']").exists())
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/auth/dev-token'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/profile']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/profile/favorite-team']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/profile/username-availability']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.summary")
                        .value("온보딩 진행 상태 조회"))
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding/username'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/mypage'].get.summary")
                        .value("내 프로필 조회"))
                .andExpect(jsonPath("$.paths['/api/mypage/profile'].patch.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/mypage/profile'].patch.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/mypage/profile-image'].put.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/teams'].get.summary")
                        .value("활성 KBO 구단 목록 조회"))
                .andExpect(jsonPath("$.paths['/api/users/search'].get.summary")
                        .value("친구 추가용 사용자 검색"))
                .andExpect(jsonPath("$.paths['/api/users/search'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/users/search'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/users/search'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/friendships/requests'].post.summary")
                        .value("친구 신청"))
                .andExpect(jsonPath("$.paths['/api/friendships/requests'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/friendships/requests'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/friendships/requests'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/friendships/requests'].get.summary")
                        .value("받은 친구 신청 목록 조회"))
                .andExpect(jsonPath("$.paths['/api/friendships'].get.summary")
                        .value("친구 목록 조회"))
                .andExpect(jsonPath("$.paths['/api/friendships/{id}/accept'].post.summary")
                        .value("친구 신청 수락"))
                .andExpect(jsonPath("$.paths['/api/friendships/{id}/accept'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/friendships/{id}/reject'].post.summary")
                        .value("친구 신청 거절"))
                .andExpect(jsonPath("$.paths['/api/friendships/{id}'].delete.summary")
                        .value("친구 관계 삭제 또는 보낸 신청 취소"))
                .andExpect(jsonPath("$.paths['/api/friendships/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.components.schemas.UserSearchResponse.properties.relationshipStatus")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.FriendUserResponse.properties.email").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/health'].get.summary")
                        .value("서비스 상태 확인"));
    }

    @Test
    void everyExposedOperationHasSummaryTagAndSuccessResponseAndPublicSecurityOverrides() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode document = objectMapper.readTree(body);
        Set<String> methods = Set.of("get", "post", "put", "patch", "delete");

        document.path("paths").properties().forEach(path -> path.getValue().properties().stream()
                .filter(operation -> methods.contains(operation.getKey()))
                .forEach(operation -> {
                    JsonNode definition = operation.getValue();
                    String operationName = operation.getKey().toUpperCase() + " " + path.getKey();
                    assertThat(definition.path("summary").asText())
                            .as(operationName + " summary")
                            .isNotBlank();
                    assertThat(definition.path("tags").isArray())
                            .as(operationName + " tags")
                            .isTrue();
                    assertThat(definition.path("tags").size())
                            .as(operationName + " tags")
                            .isPositive();
                    assertThat(definition.path("responses").propertyStream()
                            .anyMatch(response -> response.getKey().startsWith("2")
                                    || (path.getKey().equals("/api/profile-images/{id}") && response.getKey().equals("302"))))
                            .as(operationName + " success response")
                            .isTrue();
                }));

        for (String publicPath : Set.of(
                "/api/health",
                "/api/auth/dev-token",
                "/api/auth/google",
                "/api/auth/refresh",
                "/api/teams")) {
            document.path("paths").path(publicPath).properties().stream()
                    .filter(operation -> methods.contains(operation.getKey()))
                    .forEach(operation -> assertThat(operation.getValue().path("security").isEmpty())
                            .as(operation.getKey().toUpperCase() + " " + publicPath + " public security")
                            .isTrue());
        }
    }
}
