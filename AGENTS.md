# Agent guide — DAD-Android

Kotlin/Jetpack Compose donor and partner app built against a vendored snapshot of DAD-Main's API
contract. Start with these; this file only adds what they do not say.

- **[README.md](README.md)** — build/test, the contract/sessions/transport model, release
  signing, dependency updates, real-backend acceptance.
- Cross-repo system docs (topology, the mobile contract, re-vendor model) live in the
  separate **DAD-Docs** repo. Read the mobile contract before any contract change.
- **[Shared agent workflow](https://github.com/pre-dad/DAD-Docs/blob/dev/conventions/agent-workflow.md)** —
  read DAD-Docs for skill setup, routing, and independent-review boundaries. Keep
  shared procedures there; this guide owns repo-local commands and constraints.

## Build and test

```sh
node scripts/check-foundation.mjs
node scripts/check-hardcoded-strings.mjs
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:lintRelease --warning-mode all
```

CI runs these on API 35 (**lint treats warnings as errors**), plus `-PdadLoadTests`, `scripts/check-release-apk.sh`,
`node --test scripts/*.test.mjs`, an advisory foundation-drift check, and the emulator suite
(`connectedDebugAndroidTest`).

User-visible text is never a literal in `app/src/main`: add it to `res/values/strings.xml` (formatting
arguments or `<plurals>` for counts, never concatenated sentences) and use `stringResource`, or return a
`UiText` from a model. `scripts/check-hardcoded-strings.mjs` enforces this in CI.

## Branch and push discipline

- Work on `dev`; ship via `dev` → PR → `main`. Never push to `main` directly.
- Run `git commit` and `git push` as two separate commands; the publish guard rejects a chained `commit && push`.

## Code comments

- Comments explain **why** (a constraint, a trade-off, a non-obvious behavior), not what the
  code already says.
- No commented-out code (git keeps history). A `TODO` names a tracked issue.

## Gotchas not written in the docs

- **`git add -A` can stage planning docs.** `docs/requirements/` (dev plan, gap analysis, PRD) is
  untracked and gitignored. Stage specific files, and check `git status` for stray `docs/` staging before
  committing.
- **Vendored contract is hash-locked.** `foundation.lock.json` pins version + 40-char
  source revision + manifest sha256; `scripts/check-foundation.mjs` verifies every file.
  Re-vendoring requires an **actual tagged** DAD-Main mobile-foundation release — bump the
  triple, copy the files, widen the OpenAPI Generator allow-list for new ops, regenerate,
  run check-foundation + build. Never generate a client from a moving branch.
- **Re-vendor also touches the test pin:** `scripts/check-foundation.test.mjs` hardcodes the
  expected version, which must match `foundation.lock.json`; bump them together.
- **Analytics payloads must match DAD-Main's ingest allow-list** (`internal/analytics`):
  string-to-string properties, extra keys are rejected. Pinned per event in `AttributionTest`.
- **Generator constraints:** `anyOf` + `type: null` and `additionalProperties: false`
  need handling (the `LogoutAcknowledgement` pattern); new nullable shapes may too.
- **Do not promote time-varying lint advisories to errors.** `NewerVersionAvailable`,
  `AndroidGradlePluginVersion` and `GradleDependency` flag that an upstream release merely exists, not a code
  defect — so `warningsAsErrors` on them breaks CI on untouched code whenever any
  dependency ships. Disable those specific checks rather than chasing version bumps.
- **The synthetic preview session is DEBUG-gated** (`check(BuildConfig.DEBUG)` in the
  session factory) so a release build with an unconfigured origin never serves synthetic
  data as real. Keep that guard on any new session fallback.
- **Never track working documents.** Plans/findings/notes stay untracked (`docs/requirements/`).
