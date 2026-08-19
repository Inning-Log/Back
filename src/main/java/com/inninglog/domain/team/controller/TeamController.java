package com.inninglog.domain.team.controller;

import com.inninglog.domain.team.dto.TeamSummaryResponse;
import com.inninglog.domain.team.service.TeamQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
@Tag(name = "구단", description = "온보딩과 프로필에서 사용하는 활성 KBO 구단 조회 API")
public class TeamController {

    private final TeamQueryService teamQueryService;

    public TeamController(TeamQueryService teamQueryService) {
        this.teamQueryService = teamQueryService;
    }

    @Operation(summary = "활성 KBO 구단 목록 조회", description = "화면 표시 순서대로 활성 구단만 반환합니다.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "활성 구단 목록 조회 성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = TeamSummaryResponse.class)))
            )
    })
    @GetMapping
    public List<TeamSummaryResponse> getTeams() {
        return teamQueryService.getActiveTeams();
    }
}
