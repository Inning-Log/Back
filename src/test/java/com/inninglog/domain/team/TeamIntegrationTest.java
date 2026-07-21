package com.inninglog.domain.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.team.entity.KboTeam;
import com.inninglog.domain.team.repository.KboTeamRepository;
import com.inninglog.domain.team.service.TeamNotFoundException;
import com.inninglog.domain.team.service.TeamQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KboTeamRepository teamRepository;

    @Autowired
    private TeamQueryService teamQueryService;

    @Test
    void migrationSeedsTenActiveTeamsInDisplayOrder() {
        assertThat(teamRepository.findAllByActiveTrueOrderByDisplayOrderAsc())
                .extracting(KboTeam::getTeamCode)
                .containsExactly("NC", "LG", "OB", "KT", "SS", "WO", "HH", "HT", "SK", "LT");
    }

    @Test
    void activeTeamListIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].teamCode").value("NC"))
                .andExpect(jsonPath("$[0].name").value("NC Dinos"))
                .andExpect(jsonPath("$[0].displayOrder").value(1))
                .andExpect(jsonPath("$[9].teamCode").value("LT"));
    }

    @Test
    void teamCodeLookupNormalizesWhitespaceAndCase() {
        KboTeam team = teamQueryService.getEntityByCode("  ob  ");

        assertThat(team.getName()).isEqualTo("Doosan Bears");
    }

    @Test
    void unknownTeamCodeIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                TeamNotFoundException.class,
                () -> teamQueryService.getEntityByCode("UNKNOWN"));
    }
}
