package com.inninglog.domain.team.controller;

import com.inninglog.domain.team.dto.TeamSummaryResponse;
import com.inninglog.domain.team.service.TeamQueryService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamQueryService teamQueryService;

    public TeamController(TeamQueryService teamQueryService) {
        this.teamQueryService = teamQueryService;
    }

    @Operation(summary = "Get active KBO teams")
    @GetMapping
    public List<TeamSummaryResponse> getTeams() {
        return teamQueryService.getActiveTeams();
    }
}
