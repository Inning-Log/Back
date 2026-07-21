package com.inninglog.domain.team.repository;

import com.inninglog.domain.team.entity.KboTeam;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KboTeamRepository extends JpaRepository<KboTeam, Long> {

    Optional<KboTeam> findByTeamCode(String teamCode);

    boolean existsByTeamCode(String teamCode);

    List<KboTeam> findAllByActiveTrueOrderByDisplayOrderAsc();
}
