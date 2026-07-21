package com.inninglog.domain.team.service;

import com.inninglog.domain.team.dto.TeamSummaryResponse;
import com.inninglog.domain.team.entity.KboTeam;
import com.inninglog.domain.team.repository.KboTeamRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TeamQueryService {

    private final KboTeamRepository teamRepository;

    public TeamQueryService(KboTeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    public List<TeamSummaryResponse> getActiveTeams() {
        return teamRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(TeamSummaryResponse::from)
                .toList();
    }

    public KboTeam getEntityById(Long teamId) {
        return teamRepository.findById(teamId)
                .filter(KboTeam::isActiveTeam)
                .orElseThrow(TeamNotFoundException::new);
    }

    public KboTeam getEntityByCode(String teamCode) {
        if (teamCode == null || teamCode.isBlank()) {
            throw new TeamNotFoundException();
        }

        String normalizedCode = teamCode.trim().toUpperCase(Locale.ROOT);
        return teamRepository.findByTeamCode(normalizedCode)
                .filter(KboTeam::isActiveTeam)
                .orElseThrow(TeamNotFoundException::new);
    }
}
