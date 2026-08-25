# AGENTS.md — basic-framework engineering standard

This file is the single source of truth for engineering rules in this
repository, adapted from the deepseek-harness engineering model
(https://github.com/deepseek-ai/deepseek-harness) to this stack:
Spring Boot 3 / Java 17 backend (`后端代码/basic-framework-boot`),
Vue 3 + TypeScript monorepo frontend (`前端代码/basic-framework-admin`),
MySQL 8 schema (`数据库文件/`).

Every mechanizable rule MUST be enforced by a blocking CI gate. A rule
that cannot fail the build is documentation, not a rule.

## Repository layout

```
后端代码/basic-framework-boot/     Maven multi-module backend
  basic-framework-dependencies/    unified dependency versions (BOM)
  basic-framework-core/            starters = capability seams
  basic-framework-module-system-api/  system 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-system/   system management module
  basic-framework-module-infra-api/   infra 对外契约薄模块（CommonApi + DTO）
  basic-framework-module-infra/    infrastructure module
  basic-framework-server/          boot assembly (the only app entry)
前端代码/basic-framework-admin/    pnpm + turbo monorepo (apps/web-ele)
数据库文件/                        schema + seed data (sanitized)
docs/                              architecture and decision records
```

## Architecture rules

- **A starter is a capability seam.** It declares a contract
  (auto-configuration + properties), provides the implementation, and is
  consumed through that contract only. Business modules never reach into
  another module's internals; cross-module calls go through the api
  package of the owning module.
- **Extension points, not core patches.** New cross-cutting behavior
  (auth, logging, rate limiting, data permission) attaches to a
  documented interceptor/filter/annotation in `basic-framework-core`.
  Changing an existing core behavior requires updating the owning
  starter's README in the same change.
- **Explicit > implicit at module boundaries.** Defaults live in the
  owning auto-configuration's `Properties` class, never as scattered
  `?? default` / hardcoded literals in business code.
- **No hardcoded tunables or secrets in code.** Anything that varies by
  deployment is a validated configuration property. Secrets come from
  environment variables; no password, token, or internal IP is committed.
- **Misconfiguration fails loud at startup**, not at first request.
  `ProductionConfigurationEnvironmentPostProcessor` is the pattern;
  extend the same fail-closed validation to every profile.
- **Registrations clean up after themselves.** Anything registered at
  runtime (jobs, listeners, caches) has a defined teardown path.

## Security baseline (fail-closed defaults)

- No account can be created without an explicit admin action: public
  registration endpoints stay deleted/disabled in seed data.
- Seed data ships sanitized: no real login history, no demo phone/email,
  no known-weak password hash, OAuth2 secrets are per-deployment values.
- Token generation uses `SecureRandom`; password hashing is BCrypt.
- Secrets that must be recoverable at rest use AES-GCM with a random nonce;
  browser-side application encryption never substitutes for TLS.
- Login, SMS, and registration-rate endpoints carry `@RateLimiter`.
- File storage validates canonical paths against traversal for every
  storage backend.
- Log files, `.env`, `docker.env`, and lockfiles with resolved
  credentials never enter the repository. Runtime logs are not
  committed; access logs redact sensitive fields.

## Testing tiers

1. **Unit** (`mvn test` / `pnpm test:unit`): service and util logic,
   edge cases, error paths, concurrency. Backend services follow the
   BaseDbUnitTest (Mockito + H2) pattern.
2. **Slice/API**: `@WebMvcTest` for controllers, `@SpringBootTest` with
   Testcontainers for integration paths that need real MySQL/Redis.
3. **Boot smoke**: the packaged jar starts and answers a health check;
   catches "green unit tests, broken product".
4. **Frontend**: component/logic specs with vitest; e2e snapshots only
   where a harness exists — dead scripts are removed, not kept.

Coverage: repository-wide ratchet, never decreasing; files changed in a
PR are held to the per-file floor. An uncovered line is a candidate for
deletion first, test second. Mock only expensive or non-deterministic
boundaries (external HTTP, clock); keep everything downstream real.
Assert on externally observable state (database rows, files, HTTP
responses), not on the system's self-report.

## CI gates (all blocking, one aggregator check)

Executable topology: `.harness/verify.ps1` on Windows and
`.harness/verify.sh` on Linux CI. Both providers expose the same named gates;
CI-specific infrastructure only supplies tool images, services, caches, and
artifacts. Every blocking job is a dependency of the single `aggregate` check.

Backend: `mvn -q verify` — compile, tests, Spotless 格式化门禁, JaCoCo
覆盖率棘轮, ArchUnit module-boundary rules. OWASP dependency check 的临时
例外及移除条件登记在 `docs/exceptions.yaml`。
Frontend: `pnpm check` (circular, dep, typecheck, cspell) + `pnpm lint`
+ `pnpm test:coverage`（包含单元测试与覆盖率棘轮），with
`pnpm-lock.yaml` committed.
Repo: pre-commit runs trailing-newline/whitespace and lint-staged via
lefthook; production build uses `--mode production` only.
Contracts: `node scripts/check-field-catalog.mjs` keeps
`docs/contracts/field-catalog.yaml` (the field-contract source of truth)
in sync with both ends' code; handling rules live in
`docs/security/data-classification.md`.
Lifecycle: `node scripts/check-data-lifecycle.mjs` keeps every final Flyway
table and physical foreign key registered in
`docs/contracts/data-lifecycle.json`; policy semantics live in
`docs/data-lifecycle.md`.

## Documentation

- One home per fact; every other mention links instead of repeating.
- Docs accompany every behavior change: the owning module's README and
  the code comments update in the same commit.
- Comments state contracts and safe-use facts, not control-flow
  narration. Javadoc on public api-package methods includes
  `@param`/`@return`.
- README files describe what actually exists; removed features are
  removed from docs in the same change.
- Non-trivial changes record a short decision note under `docs/adr/`.

## Code conventions

- Immutability first; files 200–400 lines (800 max); cyclomatic
  complexity ≤ 10; parameters ≤ 5; nesting ≤ 3; functions ≤ 50 lines.
- Every user-supplied parameter is validated: normal / boundary / null /
  overflow / special-chars / type-error.
- Closed enum/switch logic ends in a default-throw; unknown values fail,
  never silently pass.
- Empty `catch` blocks name what they swallow and why.
- No dead code: unused scripts, env vars, config keys, and mock
  scaffolding are deleted in the change that orphans them.
- `TODO` (this iteration) / `FIXME` (bug, next release) / `XXX` (known
  tech debt) carry an owner or issue reference.
- No comments restating what the code obviously does. No emoji in code,
  comments, or docs.

## Git workflow

- Conventional commits (`feat:` `fix:` `refactor:` `docs:` `test:`),
  small focused commits, local gates green before commit.
- One PR = one purpose; split independent changes. Fix a regression in
  the PR that introduced it before it propagates.
