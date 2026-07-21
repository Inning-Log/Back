package com.inninglog.domain.stadium;

import static org.assertj.core.api.Assertions.assertThat;

import com.inninglog.domain.stadium.entity.Stadium;
import com.inninglog.domain.stadium.repository.StadiumRepository;
import com.inninglog.domain.team.entity.KboTeam;
import com.inninglog.domain.team.repository.KboTeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StadiumIntegrationTest {

    @Autowired
    private StadiumRepository stadiumRepository;

    @Autowired
    private KboTeamRepository teamRepository;

    @Test
    void stadiumCanBeAssignedAsATeamsRepresentativeHomeStadium() {
        Stadium stadium = stadiumRepository.save(new Stadium("Test Baseball Stadium", "Seoul"));
        KboTeam team = teamRepository.save(new KboTeam(
                "TS",
                "Test Team",
                "Test",
                null,
                null,
                stadium,
                99,
                true));

        assertThat(team.getHomeStadium().getName()).isEqualTo("Test Baseball Stadium");
        assertThat(team.getHomeStadiumId()).isEqualTo(stadium.getId());
    }
}
