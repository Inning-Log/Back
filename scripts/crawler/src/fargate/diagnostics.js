// Never serialize HTTP errors/configs, raw HTML, credentials or arbitrary detail keys.
const DETAIL_KEYS = new Set([
  "anomalies", "type", "card", "row", "date", "code", "path", "message", "cause",
  "page", "targetDate", "stage", "away", "home", "awayScore", "homeScore",
  "pointScores", "flag", "time", "bodyBytes", "bodySha256", "attempt", "maxAttempts",
  "evidenceKey",
]);

function text(value, limit = 300) {
  return String(value ?? "").replace(/[\r\n\t]/g, " ")
    .replace(/https?:\/\/\S+/gi, "[URL]")
    .replace(/\b(?:Bearer|Basic)\s+\S+/gi, "[AUTH]")
    .replace(/\b(?:password|token|secret|api[-_]?key|authorization)\s*[:=]\s*[^\s,;]+/gi, "[REDACTED]")
    .slice(0, limit);
}

export function safeDetails(value, depth = 0) {
  if (depth > 6 || value == null) return undefined;
  if (typeof value === "string") return text(value);
  if (typeof value === "number") return Number.isFinite(value) ? value : undefined;
  if (typeof value === "boolean") return value;
  if (Array.isArray(value)) return value.slice(0, 10).map(entry => safeDetails(entry, depth + 1));
  if (typeof value !== "object") return undefined;
  return Object.fromEntries(Object.entries(value).filter(([key]) => DETAIL_KEYS.has(key))
    .map(([key, entry]) => [key, safeDetails(entry, depth + 1)]));
}

export function safeError(error) {
  return {
    level: "error",
    code: text(error?.code ?? "FARGATE_CRAWLER_FAILED", 100),
    message: text(error?.message ?? error, 500),
    details: safeDetails(error?.details),
  };
}
