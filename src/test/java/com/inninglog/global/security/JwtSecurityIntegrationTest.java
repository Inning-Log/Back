package com.inninglog.global.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void secureEndpointAcceptsIssuedBearerToken() throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/dev-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "subject": "tester",
                                  "roles": ["USER"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = JsonPath.read(responseBody, "$.accessToken");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("tester"))
                .andExpect(jsonPath("$.authorities[0]", containsString("ROLE_USER")));
    }

    @Test
    void secureEndpointRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightAllowsConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/google")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    void corsPreflightRejectsUnconfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/google")
                        .header("Origin", "https://malicious.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
