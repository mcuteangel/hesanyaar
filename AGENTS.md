# Hesabyar – Agent Guide

## Documentation Style

Write all agent-facing documentation and code comments in ASD-STE100 (Simplified Technical English):

- Use short, clear sentences (maximum 20 words per sentence).
- Use active voice.
- Use one idea per sentence.
- Use consistent, precise terms — never switch between synonyms for the same concept.
- Avoid ambiguous words, jargon, and adverbial qualifiers.

## Project Identity

Hesabyar is a personal finance app for Android. It is Persian-first. It works offline. AI support (Gemini/OpenRouter) is optional. It is not part of the main app.

## Build & Run

```bash
# Debug build (only needs GEMINI_API_KEY in .env)
./gradlew --no-daemon installDebug

# Release signing (requires .env with KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
./gradlew --no-daemon generateKeystore   # first time only
./gradlew --no-daemon checkSigningConfig  # verify signing config

# Run all unit tests (non-Rust + Rust isolated)
./gradlew --no-daemon test

# Run fast (non-Rust) tests only — no JNI fork overhead (~2m vs ~7m)
./gradlew --no-daemon testDebugUnitTest

# Run Rust-bridge tests only — with JNI isolation (forkEvery=1)
./gradlew --no-daemon testDebugUnitTestRust

# Run single non-Rust test class (fast task — Rust-tagged classes are excluded here)
./gradlew --no-daemon testDebugUnitTest --tests "io.github.mojri.hesabyar.TransactionTest"

# Run single Rust-tagged test class (must use the isolated task)
./gradlew --no-daemon testDebugUnitTestRust --tests "io.github.mojri.hesabyar.rust.AiAdviceSanitizationTest"

# Lint / static analysis (no custom config, uses Android defaults)
./gradlew --no-daemon lint
```

Run `./gradlew --no-daemon compileDebugKotlin` before a broad test run. This check finds Kotlin type errors early. This check is optional. The test tasks compile test sources separately.

For release-variant Kotlin compilation, use this command. This type-check only. It does not use signing or ProGuard. Use the signing checks above for those tasks.

```bash
./gradlew --no-daemon compileReleaseKotlin
```

For release packaging, signing, and ProGuard verification, run the configured release `assemble` or `bundle` task. For example, run `./gradlew assembleRelease` or `./gradlew bundleRelease`.

## Test Reliability: Rust JNI State Leakage

The Rust native library (`hesabyar_core`) uses global mutable state. You cannot reset this state between test classes that share the same JVM. Tests that use the Rust bridge include `@Category(RustTest::class)`. These tests run in a separate Gradle task (`testDebugUnitTestRust`) with `forkEvery=1` and `maxParallelForks=1`.

Before you merge a change to Rust bridge code, Rust FFI tests, or test infrastructure, always verify with a cache-busting run:

```bash
# rerun-tasks (re-executes tasks in the selected Gradle task graph without deleting build artifacts)
# Do NOT use `clean` — it forces full binary/NDK rebuilds and can hit Windows
# file-lock failures on `app/build` (e.g. open R.jar) when a daemon lingers.
./gradlew --no-daemon test --rerun-tasks
```

A plain `./gradlew --no-daemon test` can report "BUILD SUCCESSFUL" from stale cached results. The tests can still fail. This is dangerous after a change to `RustIsolationRule`, `HesabyarApp`, or `RustBridge`.

## Test Override Semantics: `setRustInitializedForTesting`

The Rust availability override comes from `HesabyarApp.setRustInitializedForTesting(value)`. It is a decision override. `ensureRustInitialized()` checks it before it tries to load the native library. It is not a memoization reset.

If you set it to `false`, every `RustBridge` caller uses the Kotlin fallback. This happens even when `hesabyar_core` is loadable. `ensureRustInitialized()` returns the override before it calls `System.loadLibrary`.

Before this change, `false` only reset the inited flag. The library was re-loaded on the next access. Then the "fallback" tests used the Rust path when the DLL was on `java.library.path`. Per `app/build.gradle.kts`, this is true for every test task.

Rule of thumb for test authors: a test that claims to cover the fallback or the Kotlin path must use one of these options:

- Call the Kotlin function directly.
- Pair `setRustInitializedForTesting(false)` with `RustIsolationRule`. This saves, clears, and restores the override per class.

`setRustInitializedForTesting(false)` alone forces Kotlin fallback execution for every caller. Still pair it with `RustIsolationRule` so the process-global override is saved, cleared, and restored after the test class — otherwise it leaks into every later test class.

## Environment Setup

1. Copy `.env.example` to `.env`.
2. Set `GEMINI_API_KEY`. This is required for AI features. It is not required for the core app.
3. For release builds, set `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.
4. The secrets plugin maps `.env` to `BuildConfig` fields.

## Hard Constraints

The system rejects any change that breaks these constraints:

- Do not use `Float` or `Double` for money. Use `Long` (Rial) or `BigDecimal`.
- Do not use destructive Room migrations. A schema change must preserve existing data.
- Do not hardcode API keys. Use `.env` or Keystore for all secrets.
- Do not remove the Jalali calendar or offline support.
- Do not implement new feature business logic in Kotlin. New feature rules, calculations, validations, and rule-driven data transformations MUST go in the Rust core (`rust/hesabyar-core`). UI rendering, persistence, and adapter/mapping code (DTO conversion, entity mapping) stay normal Kotlin territory and need no exception-list justification. Kotlin fallbacks for business logic are permitted only for the pre-approved exceptions. See `## Business Logic Policy` below.
- Do not use `GlobalScope`. Use structured coroutine scopes.

## Architecture

This is a single-module Android app. The package root is `io.github.mojri.hesabyar`.

```
ui/           → Screens (Compose), ViewModels, Theme
api/          → AI providers (GeminiParser, BudgetAdvisor, AiProvider interface)
data/         → Room entities, DAOs, Repository, ExcelExporter, BackupModels
rust/         → UniFFI bridge (RustBridge.kt), generated bindings (hesabyar_core.kt)
reminder/     → WorkManager workers, notification helpers
rust/hesabyar-core/ → Rust core crate (all business logic, calculations, advisory)
```

The data flow is: `Screen → ViewModel → UseCase → RustBridge → Rust core (business logic)` alongside `ViewModel/UseCase → Repository → Room/Network (persistence)`. Some existing ViewModels (e.g., AccountViewModel, AnalyticsViewModel) call the Repository directly; new code should prefer the Use Case layer.


## Key Patterns

- Use MVVM and Use Cases. Business logic lives in the Rust core (`rust/hesabyar-core`); Kotlin ViewModels, UseCases, and the Repository orchestrate calls to Rust via `RustBridge` and handle Android-specific concerns (persistence, UI state, DI). They are NOT where new business logic should be added.
- Use the Jalali calendar through `JalaliCalendarHelper.kt`. All dates use it. Do not use `java.time.LocalDate` directly.
- Use the AI abstraction. `AiProvider` is the interface with `AiProviderConfig`. Business logic must not link to a specific provider.
- Use the Persian-first UX. Use full RTL, the Vazirmatn font, and Persian terms in the UI strings.

## Business Logic Policy

Rust Core (`rust/hesabyar-core`) is the sole location for new business logic. Kotlin fallbacks are permitted ONLY for the pre-approved exception list. See `docs/architecture/ADR-001-rust-sole-implementation.md` (`## Decision` and `### Permanent Kotlin Fallbacks (Exception List)`) for the full policy, exception list, and the phased removal plan for non-exception fallbacks.

## Testing

- Put unit tests in `app/src/test/`. Use JUnit, Robolectric, and Roborazzi (screenshot testing).
- There are no Android instrumentation tests. `app/src/androidTest/` is empty.
- The test config is in `app/build.gradle.kts`. It uses `isIncludeAndroidResources = true` and `isReturnDefaultValues = true`.

## Checklist Before You Change Code

1. Does this break offline functionality?
2. Does this bypass the Jalali calendar?
3. Does this affect financial calculation accuracy?
4. Does this require a Room migration?
5. Are local backups still compatible?
6. Does this introduce new business logic in Kotlin that should be in the Rust core instead? (See `## Business Logic Policy`.)

## Mandatory Development Guidelines

Before you write or refactor code, verify the implementation against these principles.

### 1. Modular and Reusable Architecture (DRY)

- Define shared methods, utility functions, components, state formatters, and models once in a shared package. Examples are `ui/components/`, `ui/utils/`, and `core/`. Reuse them in every screen. One-off local logic, tests, documentation, and configuration changes are exempt.
- Do not duplicate shared helper functions inside screens. Do not duplicate UI elements inside screens. If you need a logic or a UI piece in more than one place, extract it into a shared reusable module.

### 2. Strict Material Design 3 (M3) Standards

- Always use the semantic Material3 tokens. Use `MaterialTheme.colorScheme.onSurfaceVariant`, `surfaceContainerLowest`, and `MaterialTheme.typography.*`.
- Do not hardcode manual colors, magic numbers, or arbitrary color alphas. An example is `onSurface.copy(alpha = 0.5f)`. If a design token is missing, define it in the design system or the theme module. Examples are `Theme.kt` and `Color.kt`. This keeps the screens consistent in the Light and Dark themes.

### 3. Minimalist Code and Zero Redundancy

- Keep the implementation clean and minimal. Remove redundant wrapper code and dead logic.
- Scan the file for duplication and anti-patterns when you modify it. Refactor and optimize them as part of the task.


### 4. JUnit `assertEquals` Argument Order

The 3-argument `assertEquals` signature is `assertEquals(String message, expected, actual)`. It is not `assertEquals(expected, actual, String message)`. If you put the message last, there is a compile-time type mismatch (`Int` vs `String`). Always put the message first:

```kotlin
// Correct
assertEquals("Should have 2 distinct orders", 2, orders.size)

// Wrong — compile error
assertEquals(2, orders.size, "Should have 2 distinct orders")
```

### 5. Test Naming Convention (Codacy Compliance)

- Do not use backtick-quoted test names. Codacy flags `` `fun \`name with spaces\`` `` as a violation of `[a-z][a-zA-Z0-9]*`. Use camelCase:
  - Bad: `` fun `putForecast then getForecast returns same value`() ``
  - Good: `fun putForecastThenGetForecastReturnsSameValue()`
- When you touch an existing backtick test, rename it to camelCase as part of the change.
- Use camelCase names for all new test files.

## Rust Changes Require Binding Regeneration

The Kotlin side talks to the Rust core (`rust/hesabyar-core`) through the UniFFI bindings. The bindings are generated into `app/src/main/java/io/github/mojri/hesabyar/rust/hesabyar_core.kt`.

- After any change to the Rust source (`rust/**`), regenerate the Kotlin FFI bindings and the host library. Otherwise, the build or the FFI calls do not reflect the change.
- Run `./gradlew --no-daemon :app:generateAndFixBindings`. The alias `:app:generateRustBindings` skips the package-patch/install step.
- Do not edit the generated `hesabyar_core.kt` manually. The task overwrites it.

> Doc comments are part of the UniFFI API checksum. A comment-only change to an exported (`#[uniffi::export]`) function still requires binding regeneration. Then the host library fails the `uniffiCheckApiChecksums` check at load time. Every Rust-tagged test fails with "UniFFI API checksum mismatch" before the test logic runs.
> Locally, the regeneration tasks do nothing unless you force them (`outputs.upToDateWhen`). Use `./gradlew --no-daemon :app:generateAndFixBindings --rerun-tasks`. The generated `hesabyar_core.kt` is gitignored. Regeneration leaves no git diff.
>
> This is a hand-maintained compat object. The task always appends `app/buildSrc/template/HesabyarCore.template.kt` to the generated bindings. It does not patch that template's signatures.
> When a Rust FFI function's signature changes (new, removed, or reordered parameters), update the matching line in that template. Add defaults for any new trailing parameter. Then run `:app:generateAndFixBindings` again.
> Otherwise, the repo `hesabyar_core.kt` has a stale `HesabyarCore.xxx()` wrapper. The wrapper calls the regenerated top-level function with the wrong argument count.


## Rust Core Versioning

The core is bundled with the app. It is not published separately. It has its own versioning scheme. It is independent from the Android app version (root `VERSION` file).

- The base version (`MAJOR.MINOR.PATCH`) lives in `rust/Cargo.toml` `[workspace.package].version`. Bump it manually per SemVer:
  - MAJOR — a breaking change to the FFI surface or the backup schema (`BackupPayload.version`).
  - MINOR — a backward-compatible feature or category added to the core API.
  - PATCH — a bug fix with no API or schema change.
- The build metadata (`+<hash>`) is auto-derived. The Gradle `:app:syncCoreVersion` task derives it from a SHA-256 of the `rust/hesabyar-core/src` tree. It is written to the gitignored `rust/hesabyar-core/src/generated/core_version.rs`. It is embedded through `build.rs` into the `CORE_VERSION` env. At runtime, it becomes available through `get_core_version()` (UniFFI). The metadata changes when the core source changes. The bundled core version reflects the exact build.
- Do not hand-edit `src/generated/core_version.rs`. It is regenerated on every binding or NDK build. `cargo build` and `cargo test` outside Gradle use the Cargo package version.

### Backup schema version (`version` / `appVersion`)

The backup envelope carries two version fields. They are independent from the app `VERSION` file and the core `CORE_VERSION`.

- `version` is the backup format/schema version. The single source of truth is the Rust const `BACKUP_SCHEMA_VERSION` in `hesabyar-core/src/models/mod.rs`. The Kotlin side derives `BuildConfig.BACKUP_SCHEMA_VERSION` from it at build time (see `app/build.gradle.kts`). They cannot drift. Bump it only on a breaking change to the serialized backup structure.
- `appVersion` is the app version that made the backup. At export time, it is written as `BuildConfig.VERSION_NAME` (Kotlin) or `env!("CORE_VERSION")` (Rust default). Do not hardcode a placeholder like `"1.0"`.

## Reference Docs

- `docs/TECH_STACK.md` — the official dependency list
- `docs/ROADMAP.md` — the feature status
- `docs/architecture/ARCHITECTURE.md` — the full architecture guide
- `docs/architecture/ADR-001-rust-sole-implementation.md` — Rust-first business logic policy decision record

## Mandatory Post-Modification Verification Workflow

Every time you modify, refactor, or introduce code, do the verification steps below. Do this before you mark the task complete or ask for feedback. Do not skip these steps. The user can override this workflow. A trivial documentation change or an edit with no behavior change is exempt.

### 1. Static Analysis and Linting (Detekt and ktlint)

First, fix the code-style violations (formatting, imports, and so on):

```bash
./gradlew ktlintFormat --no-daemon
```

Then run the linting and static analysis checks. This makes sure there are no cognitive-complexity or style regressions:

```bash
./gradlew ktlintCheck detekt --no-daemon
```

### 2. Unit Testing Suite

Run the local testing suite. This makes sure all components and boundaries work correctly.

**All Kotlin tests (non-Rust + Rust isolated):**

```bash
./gradlew test --no-daemon
```

**Fast iteration (non-Rust tests only — ~4m vs ~10m combined):**

```bash
./gradlew testDebugUnitTest --no-daemon
```

**Rust-bridge tests only (when you changed Rust bridge code):**

```bash
./gradlew testDebugUnitTestRust --no-daemon
```

**Rust Core Tests (if you changed Rust modules):**

```bash
cargo test
```


### 3. Debugging and Auto-Correction

If ktlint still fails after the initial `ktlintFormat`, try another auto-fix:

```bash
./gradlew ktlintFormat --no-daemon
```

If detekt fails, fix the findings manually. `ktlintFormat` does not resolve detekt issues.

If compilation or tests fail, analyze the logs immediately. Find the root cause. Apply the fix. Run the full verification loop again until all checks pass.

### Constraints

- Keep this workflow readable and well-structured in `AGENTS.md`.
- Do not overwrite the existing instructions. Only append or integrate this verification lifecycle cleanly.

## Evidence Standard for Completion Reports

When you report that a task, fix, or test is "done," "fixed," "already passes," or "pre-existing," always include the evidence below without a prompt:

1. The exact current code for any changed logic. Paste the real file contents (or the relevant function/block) as it exists on disk now. Do not give a diff summary, a description of what changed, or a paraphrase.
2. Raw test-runner output identified by the exact test function name. For example, the JUnit XML `<testcase>` line or the cargo test per-test `test X ... ok` line. Aggregate counts like "all tests pass" or "39 suites, 0 failures" are not enough by themselves. Pair them with the specific named test(s) for the claim.
3. Exact file paths and line numbers for anything you reference.

This applies with extra weight to a claim that something was "pre-existing," "already fixed," or "already covered by a test." Back these claims with `git blame`, `git log`, or the actual pre-existing code. Do not use an assumption.

Do not summarize test or build success as "passed" without the underlying raw evidence. Include the evidence proactively for any non-trivial change (new tests, bug fixes, security/data-integrity changes).

> Reason: this project had many cases where a summary described work (specific test names, specific fixes) that did not exist in the committed code. Treat this as a standing requirement. Do not apply it only when asked.

## Detekt Findings: Fix on Touch, Never Re-Baseline

The `config/detekt/detekt-baseline.xml` is a frozen snapshot of pre-existing findings in files that are NOT being changed. It keeps the build and CI green for legacy code. It is NOT a place to hide new work.

The only new Detekt `@Suppress` annotations permitted are the two documented exceptions under Allowed Suppressions below. Existing inline suppressions outside these two remain as legacy. Remove each Detekt suppression when you touch its file and fix the underlying finding.

### Rule: editing a file obligates fixing its findings

When you modify a file, every detekt finding in that file — even one that was previously baselined — must be fixed by splitting and refactoring. Do not:

- Add a `@Suppress` (except the two documented exceptions).
- Re-add or keep the finding's entry in `detekt-baseline.xml`.

Mechanism: a baseline entry is keyed by signature. When you edit a signature, the old entry stops matching. The finding surfaces. Fix it in the same change. If the signature is unchanged and the entry still matches, fix the finding anyway. Then remove the entry from the baseline.

Pre-existing findings in files you do NOT touch may stay baselined. You must never grow the baseline. Never add a new entry for code you introduce or modify.

If your change makes a class or function cross a threshold (for example, detekt `LargeClass` on a test class), split it into a new, smaller class or file, remove the old baseline entry for that class, and fix the findings.


### Allowed Suppressions (with a justification)

- `@Suppress("LongMethod")` in test files. Test functions are naturally longer due to Arrange-Act-Assert blocks, multiple assertions, and test data setup. This is the only acceptable context for this suppression.
- `@Suppress("TooGenericExceptionCaught")` in only two cases:
  1. Rethrowing `CancellationException` in coroutine scopes (structured concurrency).
  2. Safety-net `catch` blocks where an API layer can throw unchecked runtime exceptions. Examples are Rust FFI `RustBridge.rustCallSync` rethrowing `RuntimeException`, and org.json `opt*` accessors throwing NPE on malformed JSON. Put the annotation on the enclosing function, not inside the catch body. Always add a justification comment. See `ExportViewModel.exportExcel()` and `BackupJsonParser.parseBackupJsonKotlin()` for the pattern.
- Use camelCase test names per the [Test Naming Convention](#5-test-naming-convention-codacy-compliance). Do not use backtick-quoted names.

### Forbidden Suppressions

- `@Suppress("MagicNumber")` — extract constants or use descriptive variables.
- `@Suppress("UnusedPrivateMember")` — remove dead code; do not hide it.
- `@Suppress("LongParameterList")` — refactor into data classes or builder patterns.
- `@Suppress("ComplexMethod")` / `@Suppress("CognitiveComplexMethod")` — decompose into smaller, named functions.
- Any suppression used to avoid fixing the underlying issue.

### Refactoring Strategy for Detekt Failures

1. Long functions — extract named helper functions until the main function reads as a high-level workflow.
2. Magic numbers — extract to `companion object` constants or named `val`s with descriptive names.
3. Long parameter lists — group related parameters into data classes or use a builder.
4. Complex methods — decompose the conditional logic into small, well-named functions.
5. Cognitive complexity — restructure the control flow. Prefer early returns over deep nesting.

If a detekt rule does not apply to a specific file, the only sanctioned response is the documented `@Suppress("LongMethod")` exception in test files. Other suppressions are forbidden except the documented `@Suppress("TooGenericExceptionCaught")` cases (Rust FFI rethrow and org.json malformed-JSON access).

## Code Intelligence: Serena

Serena is the code-intelligence backend for this repo. Use it to inspect code. Do not add another indexing server.

### Project setup

- The serena MCP server is active on this machine. The project is registered and activated (read `mem:core` first; it links to the others).
- Project memories (5): `mem:core` (map + invariants), `mem:tech_stack`, `mem:suggested_commands`, `mem:conventions`, `mem:task_completion`.
- Author new project knowledge with `write_memory`. Follow the rules in the memory `memory_maintenance`.
- If a memory mention seems stale, run `serena memories check` from the project root.

### Usage pointers

- Use `get_symbols_overview` to orient on a file, `find_symbol` to locate a symbol, `find_referencing_symbols` to trace callers, `find_declaration` for a definition.
- Line numbers returned by serena are 0-based.
- Do not bypass serena with bulk reads when a symbolic tool answers the question.

