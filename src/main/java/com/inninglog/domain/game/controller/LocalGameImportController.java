package com.inninglog.domain.game.controller;

import com.inninglog.domain.game.ingestion.GameImportException;
import com.inninglog.domain.game.ingestion.GameSnapshotImporter;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Same explicit local-only gate as development token issuance. No production score-write API. */
@Hidden
@RestController
@Profile("local")
@ConditionalOnProperty(prefix = "app.auth", name = "dev-token-enabled", havingValue = "true")
@RequestMapping("/api/dev/game-snapshots")
public class LocalGameImportController {
    private final GameSnapshotImporter importer;
    public LocalGameImportController(GameSnapshotImporter importer) { this.importer = importer; }
    @PostMapping
    public GameSnapshotImporter.ImportResult ingest(@RequestBody String body) { return importer.importMessage(body); }
    @ExceptionHandler(GameImportException.class)
    public ResponseEntity<Map<String,String>> invalid(GameImportException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_GAME_SNAPSHOT", "message", error.getMessage()));
    }
}
