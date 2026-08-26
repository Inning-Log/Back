export class ProviderSchemaError extends Error {
  constructor(provider, message, details = {}) {
    super(`${provider}: ${message}`);
    this.name = "ProviderSchemaError";
    this.code = "PROVIDER_SCHEMA_MISMATCH";
    this.provider = provider;
    this.details = details;
  }
}

export function getPath(object, pathExpression) {
  if (!object || typeof object !== "object") return undefined;
  return String(pathExpression)
    .split(".")
    .reduce((value, key) => value?.[key], object);
}

export function firstPresent(object, paths) {
  for (const path of paths) {
    const value = getPath(object, path);
    if (value !== undefined && value !== null && value !== "") return value;
  }
  return undefined;
}

export function arrayAtKnownPath(payload, paths, provider, label) {
  if (Array.isArray(payload)) return payload;
  for (const candidate of paths) {
    const value = getPath(payload, candidate);
    if (Array.isArray(value)) return value;
  }
  throw new ProviderSchemaError(
    provider,
    `${label} 배열을 알려진 경로에서 찾지 못했습니다.`,
    { expectedPaths: paths }
  );
}

export function parseJsonEnvelope(raw, provider) {
  let payload = raw;
  if (typeof payload === "string") {
    try {
      payload = JSON.parse(payload);
    } catch (error) {
      throw new ProviderSchemaError(provider, "응답이 JSON이 아닙니다.", {
        cause: error.message,
      });
    }
  }
  if (typeof payload?.d === "string") {
    try {
      payload = JSON.parse(payload.d);
    } catch (error) {
      throw new ProviderSchemaError(provider, "ASMX d envelope JSON이 손상되었습니다.", {
        cause: error.message,
      });
    }
  } else if (
    payload &&
    typeof payload === "object" &&
    Object.hasOwn(payload, "d") &&
    payload.d &&
    typeof payload.d === "object"
  ) {
    payload = payload.d;
  }
  if (!payload || typeof payload !== "object") {
    throw new ProviderSchemaError(provider, "응답 최상위가 객체/배열이 아닙니다.");
  }
  return payload;
}

export function buildUrl(template, replacements) {
  const rendered = String(template).replace(/{([A-Za-z0-9_]+)}/g, (_, key) => {
    if (!Object.hasOwn(replacements, key)) {
      throw new Error(`URL placeholder 값이 없습니다: ${key}`);
    }
    return encodeURIComponent(String(replacements[key]));
  });
  const url = new URL(rendered);
  if (/{[^}]+}/.test(url.toString())) {
    throw new Error("치환되지 않은 URL placeholder가 있습니다.");
  }
  return url.toString();
}

export function dateCompact(dateKey) {
  return String(dateKey).replaceAll("-", "");
}

export function assertGameIdentity(game, provider, row) {
  if (!game?.date || !game?.homeTeam?.name || !game?.awayTeam?.name) {
    throw new ProviderSchemaError(
      provider,
      `경기 식별 필드(date/homeTeam/awayTeam)가 부족합니다.`,
      { row }
    );
  }
  if (game.homeTeam.code && game.homeTeam.code === game.awayTeam.code) {
    throw new ProviderSchemaError(provider, "홈팀과 원정팀이 동일합니다.", { row });
  }
  const hasScheduleSignal =
    Boolean(game.scheduledAt || game.stadium || game.statusText) ||
    game.status !== "UNKNOWN" ||
    game.score?.home !== null ||
    game.score?.away !== null;
  if (!hasScheduleSignal) {
    throw new ProviderSchemaError(
      provider,
      "경기 식별자는 있지만 예정 시각·구장·상태·점수 필드를 모두 찾지 못했습니다.",
      { row }
    );
  }
  return game;
}

export function scheduleFieldAnomalies(game, provider, row) {
  const anomalies = [];
  for (const [missing, field] of [
    [!game.externalId?.[provider], `externalId.${provider}`],
    [!game.scheduledAt, "scheduledAt"],
    [!game.stadium, "stadium"],
    [game.status === "UNKNOWN", "status"],
  ]) {
    if (missing) {
      anomalies.push({
        provider,
        type: "MISSING_CANONICAL_FIELD",
        row,
        field,
        message: `${field}를 알려진 provider 필드에서 확인하지 못했습니다.`,
      });
    }
  }
  return anomalies;
}
