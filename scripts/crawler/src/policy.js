const DAY_MS = 24 * 60 * 60 * 1000;

export const POLICY_CATALOG = Object.freeze({
  kbo: Object.freeze({
    displayName: "KBO",
    checkedAt: "2026-08-25",
    termsUrl: "https://www.koreabaseball.com/Etc/Policy.aspx",
    robotsUrl: "https://www.koreabaseball.com/robots.txt",
    documentedApi: false,
    robotsPosture: "disallow",
    robotsExceptionRequired: true,
    defaultVerdict: "BLOCKED",
    reason:
      "KBO robots.txt는 일반 봇 전체를 차단하고, 이용약관은 사전 서면 동의 또는 공식 API 없는 반복 자동수집과 재배포를 금지합니다.",
  }),
  naver: Object.freeze({
    displayName: "Naver Sports",
    checkedAt: "2026-08-25",
    termsUrl: "https://policy.naver.com/rules/disclaimer.html",
    robotsUrl: "https://api-gw.sports.naver.com/robots.txt",
    documentedApi: false,
    robotsPosture: "unavailable",
    robotsExceptionRequired: true,
    defaultVerdict: "BLOCKED",
    reason:
      "네이버 법적고지는 서비스/API 서버 자동수집을 원칙적으로 금지하며 공개 Sports API 문서·이용 라이선스와 API host의 허용 robots 규칙을 확인할 수 없습니다.",
  }),
});

export class PolicyError extends Error {
  constructor(message, report = null) {
    super(message);
    this.name = "PolicyError";
    this.code = "POLICY_BLOCKED";
    this.report = report;
  }
}

export function selectedProviderNames(source) {
  if (source === "fixture" || source === "manual") return [];
  if (source === "hybrid") return ["kbo", "naver"];
  if (source === "kbo" || source === "naver") return [source];
  return [String(source ?? "")].filter(Boolean);
}

function validDate(value) {
  if (!value) return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function addCheck(checks, provider, id, ok, message) {
  checks.push({ provider, id, ok: Boolean(ok), message });
}

function normalizedScopes(authorization) {
  return new Set(
    (authorization?.scopes ?? []).map((scope) => String(scope).toLowerCase())
  );
}

export function buildPolicyReport(config, options = {}) {
  const now = options.now ?? new Date();
  const providerNames = selectedProviderNames(config.source);
  const checks = [];
  const maxAgeMs =
    config.policy?.authorizationReviewMaxAgeMs ?? 7 * DAY_MS;

  if (providerNames.length === 0) {
    addCheck(
      checks,
      "fixture",
      "offline-source",
      true,
      "fixture/manual 소스는 외부 네트워크를 사용하지 않습니다."
    );
  }

  addCheck(
    checks,
    "global",
    "kill-switch",
    providerNames.length === 0 || !config.policy?.killSwitch,
    config.policy?.killSwitch
      ? "전역 네트워크 kill switch가 켜져 있습니다."
      : "전역 네트워크 kill switch가 꺼져 있습니다."
  );

  for (const name of providerNames) {
    const provider = config.providers?.[name];
    const catalog = POLICY_CATALOG[name];
    const authorization = provider?.authorization ?? {};
    const catalogCheckedAt = validDate(catalog?.checkedAt);
    const reviewedAt = validDate(authorization.reviewedAt);
    const expiresAt = validDate(authorization.expiresAt);
    const reviewFresh =
      reviewedAt &&
      now.getTime() >= reviewedAt.getTime() &&
      now.getTime() - reviewedAt.getTime() <= maxAgeMs;

    addCheck(
      checks,
      name,
      "known-provider",
      Boolean(provider && catalog),
      provider && catalog
        ? "정책 manifest가 존재합니다."
        : "지원되거나 검토된 provider가 아닙니다."
    );
    addCheck(
      checks,
      name,
      "policy-catalog-fresh",
      Boolean(
        catalogCheckedAt &&
          now.getTime() >= catalogCheckedAt.getTime() &&
          now.getTime() - catalogCheckedAt.getTime() <= maxAgeMs
      ),
      catalogCheckedAt &&
        now.getTime() >= catalogCheckedAt.getTime() &&
        now.getTime() - catalogCheckedAt.getTime() <= maxAgeMs
        ? "공식 정책 원문 검토일이 유효합니다."
        : "내장 공식 정책 검토일이 지났습니다. 원문을 재검토하고 코드 카탈로그를 갱신해야 합니다."
    );
    addCheck(
      checks,
      name,
      "provider-enabled",
      provider?.enabled === true,
      provider?.enabled
        ? "provider가 활성화되어 있습니다."
        : "provider가 비활성화되어 있습니다."
    );
    addCheck(
      checks,
      name,
      "written-authorization",
      authorization.status === "approved" &&
        typeof authorization.evidence === "string" &&
        authorization.evidence.trim().length >= 10,
      authorization.status === "approved"
        ? "승인 상태와 증빙 식별자를 확인했습니다."
        : "서면 허가 또는 공식 API 이용권한 증빙이 없습니다."
    );
    addCheck(
      checks,
      name,
      "authorization-review-fresh",
      Boolean(reviewFresh),
      reviewFresh
        ? "권한·약관 검토일이 유효합니다."
        : "권한·약관 검토일이 없거나 검토 유효기간을 지났습니다."
    );
    addCheck(
      checks,
      name,
      "authorization-not-expired",
      Boolean(expiresAt && expiresAt.getTime() > now.getTime()),
      expiresAt && expiresAt.getTime() > now.getTime()
        ? "승인 만료일이 지나지 않았습니다."
        : "승인 만료일이 없거나 이미 지났습니다."
    );
    addCheck(
      checks,
      name,
      "schedule-scope",
      normalizedScopes(authorization).has("schedule"),
      normalizedScopes(authorization).has("schedule")
        ? "일정 수집 범위가 승인되어 있습니다."
        : "일정 수집 범위 승인이 없습니다."
    );
    addCheck(
      checks,
      name,
      "documented-api",
      catalog?.documentedApi === true ||
        (config.policy?.allowUndocumentedApi === true &&
          authorization.undocumentedApiApproved === true),
      catalog?.documentedApi
        ? "공개 문서화된 API입니다."
        : "비공개/미문서 endpoint 사용에 대한 명시적 승인 범위가 없습니다."
    );
    const scopes = normalizedScopes(authorization);
    addCheck(
      checks,
      name,
      "robots-exception",
      catalog?.robotsExceptionRequired !== true ||
        (authorization.robotsExceptionGranted === true &&
          scopes.has("robots-disallow-override")),
      catalog?.robotsExceptionRequired !== true
        ? "실행 시 robots 원문을 다시 검사합니다."
        : authorization.robotsExceptionGranted &&
          scopes.has("robots-disallow-override")
        ? "robots 차단·부재 상황의 자동 접근까지 허가 범위에 포함됐다고 기록되어 있습니다."
        : "robots 차단 또는 허용 규칙 부재를 뒤집는 명시적 예외 승인과 robots-disallow-override scope가 없습니다."
    );
    if (name === "naver" && config.data?.includePlayDescriptions === true) {
      addCheck(
        checks,
        name,
        "relay-text-scope",
        normalizedScopes(authorization).has("relay-text"),
        normalizedScopes(authorization).has("relay-text")
          ? "경기 설명 원문 수집 범위가 명시적으로 승인되어 있습니다."
          : "경기 설명 원문에는 별도의 relay-text 승인이 필요합니다."
      );
    }
  }

  if (providerNames.length > 0) {
    const contact = String(config.identity?.contact ?? "").trim();
    addCheck(
      checks,
      "global",
      "bot-contact",
      /^(mailto:|https:\/\/)/i.test(contact) &&
        !/example\.invalid/i.test(contact),
      /^(mailto:|https:\/\/)/i.test(contact) &&
        !/example\.invalid/i.test(contact)
        ? "봇 연락처가 설정되어 있습니다."
        : "User-Agent에 사용할 실제 mailto: 또는 https:// 연락처가 필요합니다."
    );
  }

  return {
    checkedAt: now.toISOString(),
    source: config.source,
    enforcement: config.policy?.enforcement ?? "strict",
    allowed: checks.every((check) => check.ok),
    checks,
    catalog: Object.fromEntries(
      providerNames
        .filter((name) => POLICY_CATALOG[name])
        .map((name) => [name, POLICY_CATALOG[name]])
    ),
  };
}

export function assertPolicyAllowed(config, options = {}) {
  const report = buildPolicyReport(config, options);
  if (!report.allowed) {
    const failures = report.checks
      .filter((check) => !check.ok)
      .map((check) => `${check.provider}/${check.id}: ${check.message}`)
      .join("\n");
    throw new PolicyError(
      `정책 검증에 실패하여 외부 요청을 시작하지 않았습니다.\n${failures}`,
      report
    );
  }
  return report;
}

export function assertProviderCapability(config, providerName, capability) {
  const provider = config.providers?.[providerName];
  const scopes = normalizedScopes(provider?.authorization);
  const requested = String(capability).toLowerCase();
  const allowed =
    scopes.has(requested) ||
    (requested === "schedule" && scopes.has("schedule:read")) ||
    (["relay", "record"].includes(requested) && scopes.has("detail:read")) ||
    (providerName === "fixture" && scopes.has("fixture:read"));
  if (!provider?.enabled || !allowed) {
    throw new PolicyError(
      `${providerName}의 '${capability}' 수집 범위가 활성화·승인되지 않았습니다.`
    );
  }
}

export function formatPolicyReport(report) {
  const lines = [
    `policy: ${report.allowed ? "ALLOW" : "BLOCK"}`,
    `source: ${report.source}`,
    `checkedAt: ${report.checkedAt}`,
  ];
  for (const check of report.checks) {
    lines.push(
      `${check.ok ? "[OK]" : "[BLOCK]"} ${check.provider}/${check.id} - ${check.message}`
    );
  }
  return lines.join("\n");
}
