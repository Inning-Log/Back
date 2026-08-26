import test from "node:test";
import assert from "node:assert/strict";
import { buildPolicyReport } from "../src/policy.js";

function baseConfig(source) {
  return {
    source,
    identity: { contact: "mailto:team@example.edu" },
    policy: {
      enforcement: "strict",
      killSwitch: false,
      allowUndocumentedApi: false,
      authorizationReviewMaxAgeMs: 7 * 24 * 60 * 60 * 1000,
    },
    providers: {
      kbo: {
        enabled: false,
        documentedApi: false,
        authorization: {
          status: "unverified",
          evidence: "",
          reviewedAt: null,
          expiresAt: null,
          scopes: [],
          robotsExceptionGranted: false,
          undocumentedApiApproved: false,
        },
      },
      naver: {
        enabled: false,
        documentedApi: false,
        authorization: {
          status: "unverified",
          evidence: "",
          reviewedAt: null,
          expiresAt: null,
          scopes: [],
          robotsExceptionGranted: false,
          undocumentedApiApproved: false,
        },
      },
    },
  };
}

test("fixture는 외부 provider 승인 없이 허용된다", () => {
  const report = buildPolicyReport(baseConfig("fixture"), {
    now: new Date("2026-08-25T00:00:00Z"),
  });
  assert.equal(report.allowed, true);
});

test("KBO는 기본 설정에서 네트워크 요청 전에 차단된다", () => {
  const report = buildPolicyReport(baseConfig("kbo"), {
    now: new Date("2026-08-25T00:00:00Z"),
  });
  assert.equal(report.allowed, false);
  assert.ok(report.checks.some((check) => !check.ok && check.id === "written-authorization"));
  assert.ok(report.checks.some((check) => !check.ok && check.id === "robots-exception"));
});

test("미문서 endpoint와 robots 예외를 각각 승인한 유효 설정만 통과한다", () => {
  const config = baseConfig("kbo");
  config.policy.allowUndocumentedApi = true;
  config.providers.kbo = {
    ...config.providers.kbo,
    enabled: true,
    authorization: {
      status: "approved",
      evidence: "permission-document-2026-001",
      reviewedAt: "2026-08-24T00:00:00Z",
      expiresAt: "2026-12-31T00:00:00Z",
      scopes: ["schedule", "robots-disallow-override"],
      robotsExceptionGranted: true,
      undocumentedApiApproved: true,
    },
  };
  const report = buildPolicyReport(config, {
    now: new Date("2026-08-25T00:00:00Z"),
  });
  assert.equal(report.allowed, true);

  config.providers.kbo.authorization.reviewedAt = "2026-09-01T00:00:00Z";
  const staleCatalog = buildPolicyReport(config, {
    now: new Date("2026-09-02T00:00:00Z"),
  });
  assert.equal(staleCatalog.allowed, false);
  assert.ok(
    staleCatalog.checks.some(
      (check) => !check.ok && check.id === "policy-catalog-fresh"
    )
  );
});
