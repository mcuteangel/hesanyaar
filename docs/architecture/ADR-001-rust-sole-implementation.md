# ADR-001 — Rust Core as Sole Implementation for Business Logic

**Status:** Accepted  
**Date:** 2026-08-19  
**Decision Maker:** مجتبی (project owner)  
**Context:** Rust Core Integration Audit Report (2026-08-19)

---

## Context

Hesabyar has a hybrid Kotlin/Rust architecture. The Rust core (`rust/hesabyar-core`) was created to hold shared business logic — financial calculations, NLP parsing, calendar conversion, currency formatting, budget advisory, analytics, dashboard computation, backup parsing, and validation.

The Kotlin layer (`app/src/main/java/io/github/mojri/hesabyar/`) initially contained full implementations of many features, with Rust added later as a parallel path. The Kotlin `RustBridge` calls into Rust via UniFFI when the native library is available, and falls back to Kotlin-side logic when it is not.

This dual-implementation model creates significant technical debt:

1. **Sync risk:** Rust and Kotlin implementations must produce identical results for parity tests to pass. Every Rust change requires a matching Kotlin change.
2. **Complexity:** Two code paths double the surface area for bugs, tests, and maintenance.
3. **Performance:** The Kotlin fallbacks are slower and use error-prone types (e.g., `Double` in parsers) that the Rust migration was meant to eliminate.
4. **Inconsistency:** Rust and Kotlin versions of the same feature (e.g., budget advice text) produce **different Persian output**, creating a confusing user experience depending on device state.

---

## Decision

**Rust Core (`rust/hesabyar-core`) is the sole location for new business logic.**

From this point forward, any new feature, business rule, calculation, validation, or data transformation MUST be implemented in Rust first.

### Permanent Kotlin Fallbacks (Exception List)

The following features retain Kotlin-side fallback implementations permanently. They are NOT scheduled for removal:

| Feature | Rationale |
|---------|-----------|
| Jalali calendar conversions | Android platform integration (JalaliCalendarHelper) is Android-specific |
| Currency formatting | Tied to UI display and locale formatting |
| Offline NLP parser | Must work without any native library; provides core offline entry |
| Backup JSON parse/validate | Recovery path must not depend on Rust; backup is a data-recovery lifeline |
| AI advice validation | Lightweight sanitization that must work independently of Rust |
| Person-name normalization (dedup-key derivation) | Room migrations cannot load the native library; the migration backfill must run inside the migration itself (plans/011-personal-loan-ledger-redesign.md §D4). One shared Kotlin util in `domain/utils`; runtime create/rename/merge paths reuse it so dedup semantics never drift between migration and runtime. This is persisted-identity data hygiene (entity mapping), not financial calculation |

### Temporary Kotlin Fallbacks (Scheduled for Removal)

The following features had Kotlin fallbacks that are scheduled for phased removal:

| Feature | Consolidation Plan Phase | Status |
|---------|--------------------------|--------|
| Time-to-Goal (predictTimeToGoal) | Phase 6 | Planned |
| Debt-to-Income Ratio (localCalculateDebtToIncomeRatio) | Phase 7 | Planned |
| Financial Health Score (localCalculateFinancialHealthScore) | Phase 8 | Planned |
| Offline Budget Advice (buildLocalOfflineAdvice) | Phase 9 | Planned |
| Offline Budget Forecast (buildLocalOfflineForecast) | Phase 10 | Planned |
| Analytics (computeFallbackAnalytics) | Phase 11 | Planned |
| Dashboard Data (computeFallbackDashboardData) | Phase 12 | Planned |

These removals follow a dependency-safe ordering: safety fixes (Phases 1–3) first, then feature removals ordered by risk (advisory-only features first, Dashboard last as the app's home screen).

### Phases 1–3 (Already Applied)

The following safety fixes have already been merged before this ADR was committed:

- **Phase 1:** Fixed `ensureRustInitialized()` RuntimeException gap in `HesabyarApp.kt` — restored a `catch (RuntimeException)` branch (alongside `UnsatisfiedLinkError`, `InternalException`, and `SecurityException`) so UniFFI contract/checksum mismatches no longer propagate uncaught. The branch had been dropped in commit `0c473e0` (which replaced it with `catch (SecurityException)`); commit `9111e43` restores it. `RuntimeException` is placed LAST because `SecurityException` is a subclass of `RuntimeException` (a `catch (RuntimeException)` ordered before `catch (SecurityException)` would be an unreachable catch and fail to compile).
- **Phase 2:** Fixed `rustCallSync` exception-handling consistency in `RustBridge.kt` — `CancellationException`, `InterruptedException`, `VirtualMachineError`, and `RuntimeException` all propagate untouched; only the generic `Exception` branch (which correctly handles UniFFI's `HesabyarException`, since it extends `kotlin.Exception` not `RuntimeException`) returns the fallback (logged).
- **Phase 3:** Fixed `sumOf` overflow in `localCalculateFinancialHealthScore` in `BudgetAdvisor.kt` — replaced `sumOf { it.amount }` with `fold(BigInteger.ZERO)` + saturation pattern matching Rust's `saturating_add`, preventing silent `Long` overflow/wrap on large transaction sums.

The consolidated plan lives at `plans/2026-08-19-rust-fallback-consolidation-plan.md`.

---

## Consequences

### Positive

- **Single source of truth** for every business calculation. Eliminates sync drift and parity-test fragility.
- **Performance consistency:** All devices get Rust-level performance, not just those where the native library loads.
- **Simpler mental model** for contributors: "If it's a calculation, it goes in Rust."
- **Smaller APK** once Kotlin fallbacks are removed (less dead code on the happy path).

### Negative

- Devices where the Rust native library fails to load will see controlled error states instead of degraded-but-working features. This risk is mitigated by:
  - The Rust library is bundled with the APK and loads on >99% of devices.
  - The remaining permanent fallbacks (calendar, currency, parser, backup, AI validation) still work.
  - The consolidation plan gates each removal behind a UX decision and manual device testing (Phases 11–12 specifically require this).

### Neutral

- New contributors must understand both Kotlin (UI, persistence) and Rust (business logic). The `README.md` "Getting Started" and `AGENTS.md` now document this split.
- Rust binding regeneration is now a required step for any Rust change. `AGENTS.md` §"Rust Changes Require Binding Regeneration" documents the workflow.

---

## Implementation Workflow

```text
New feature request
        ↓
Is it a NEW business rule, calculation, validation, or data transformation?
        ├── YES → Implement in rust/hesabyar-core, export via #[uniffi::export]
        │          → Regenerate bindings: ./gradlew :app:generateAndFixBindings --rerun-tasks
        │          → Call from Kotlin via RustBridge wrapper
        │          → Add Rust-side tests in rust/hesabyar-core/src/
        │          → If it replaces a Kotlin fallback, remove the Kotlin fallback after
        │            the UX/consistency parity is verified
        │
        └── NO (UI rendering, persistence, DI, Android framework integration) → Kotlin
```

---

## References

- `AGENTS.md` — `## Business Logic Policy` and `## Hard Constraints` sections
- `docs/architecture/ARCHITECTURE.md` — `## Business Logic Policy` under Core Principles
- `docs/blueprint-account-management.md` — §2.1 Layer Responsibilities, §2.2 Responsibility Matrix
- `docs/CODE_REVIEW.md` — §۹ معیارهای معماری Rust-First
- `plans/2026-08-19-rust-fallback-consolidation-plan.md` — 14-phase consolidation plan
- `rust/hesabyar-core/README.md` — Rust core module documentation
