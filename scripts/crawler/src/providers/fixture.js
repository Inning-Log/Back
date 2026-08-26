import { promises as fs } from "node:fs";
import path from "node:path";
import { normalizeGameSnapshot } from "../domain.js";
import { ProviderSchemaError } from "./utils.js";

function replaceTargetDate(value, dateKey) {
  if (Array.isArray(value)) return value.map((item) => replaceTargetDate(item, dateKey));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, replaceTargetDate(item, dateKey)])
    );
  }
  return typeof value === "string"
    ? value.replaceAll("$TARGET_DATE", dateKey)
    : value;
}

export async function fetchFixtureSchedule({ dateKey, config, crawlerRoot }) {
  const configuredPath =
    config.providers.fixture.file;
  const filePath = path.isAbsolute(configuredPath)
    ? configuredPath
    : path.resolve(crawlerRoot, configuredPath);
  let payload;
  try {
    const stat = await fs.stat(filePath);
    if (stat.size > config.request.maxResponseBytes) {
      throw new Error(
        `fixture 크기(${stat.size})가 maxResponseBytes(${config.request.maxResponseBytes})를 넘습니다.`
      );
    }
    payload = JSON.parse(await fs.readFile(filePath, "utf8"));
  } catch (error) {
    throw new ProviderSchemaError("fixture", `fixture 파일을 읽지 못했습니다: ${filePath}`, {
      cause: error.message,
    });
  }
  const rows = Array.isArray(payload) ? payload : payload?.games;
  if (!Array.isArray(rows)) {
    throw new ProviderSchemaError("fixture", "fixture 최상위 games가 배열이 아닙니다.");
  }
  const maximumEvents = config.data.maxEventsPerGame;
  const includeDescriptions = config.data.includePlayDescriptions === true;
  const games = rows.slice(0, config.run.maxGamesPerRun).map((row) => {
    const game = normalizeGameSnapshot(replaceTargetDate(row, dateKey));
    const events = maximumEvents === 0
      ? []
      : game.events.slice(-maximumEvents);
    game.events = events.map((event) => ({
      ...event,
      description: includeDescriptions ? event.description : null,
    }));
    return game;
  });
  return { games, anomalies: [] };
}
