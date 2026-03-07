# MSSQL test plan

## Goal

Add SQL Server coverage for the Spring Data JDBC / persistence path without making the default developer loop (`./gradlew test`) slow or Docker-dependent.

## Current state

- The default test app still boots against in-memory H2 in `test/testApplication.kt`.
- Test schema DDL is now split by dialect:
  - `test/schema/h2.sql`
  - `test/schema/mssql.sql`
- The bootstrap already has a dialect hook via `TestDatabaseDialect` and `initializeTestSchema(...)` in `test/testDatabaseSchema.kt`.

That means the remaining work is mostly about datasource selection and deciding where MSSQL should run.

## Recommendation

Use a **separate opt-in MSSQL test task/job**, while keeping the main `test` task on H2.

Why:
- H2 keeps the normal feedback loop fast.
- MSSQL startup is heavier and usually requires Docker or a remote server.
- Most value comes from validating SQL Server compatibility in persistence/integration tests, not from rerunning every UI/browser test on another database.

## Proposed rollout

### Phase 1 — CI coverage first

Preferred setup:
- Add a new GitHub Actions job (for example `mssql_tests`) in `.github/workflows/ci.yml`.
- Start SQL Server in that job via a **GitHub Actions service container**.
- Run a dedicated Gradle task such as `./gradlew testMssql`.

Why service container first:
- simpler than requiring local Docker parity immediately,
- no new runtime abstraction needed beyond JDBC properties,
- good enough to catch SQL Server incompatibilities on every PR.

Suggested environment for that job:
- `FLOWLITE_TEST_DB=mssql`
- `FLOWLITE_TEST_JDBC_URL=jdbc:sqlserver://localhost:1433;databaseName=flowlite;encrypt=false;trustServerCertificate=true`
- `FLOWLITE_TEST_JDBC_USERNAME=sa`
- `FLOWLITE_TEST_JDBC_PASSWORD=<from workflow env/secret>`

### Phase 2 — Local opt-in parity

After CI works, make the same `testMssql` task runnable locally for developers who have Docker or an existing SQL Server instance.

Two acceptable ways:

1. **Reuse an external/local SQL Server** via environment variables.
   - simplest implementation,
   - no extra test dependency,
   - slightly more manual for contributors.

2. **Later add Testcontainers** for local convenience.
   - best local ergonomics,
   - but heavier and not necessary for the first CI milestone.

Recommendation: start with option 1, keep Testcontainers as an optional later improvement.

## Code changes needed for implementation

1. **Make datasource selection configurable** in `test/testApplication.kt`.
   - read a property/env var such as `FLOWLITE_TEST_DB` or `flowlite.test.db`,
   - default to `h2`,
   - for `mssql`, build a `DriverManagerDataSource` from JDBC URL/username/password properties,
   - call `initializeTestSchema(ds, TestDatabaseDialect.MSSQL)`.

2. **Introduce a separate Gradle task**.
   - keep `test` mapped to H2,
   - add `testMssql` that sets the MSSQL properties and runs only the persistence/integration suites.

3. **Scope the MSSQL suite intentionally**.
   - include tests that exercise Spring Data JDBC, the engine, and cockpit service aggregation,
   - skip Playwright/browser tests because database vendor adds little extra value there.

## Suggested MSSQL test scope

Run on MSSQL:
- `OrderConfirmationTest`
- `EmployeeOnboardingFlowTest`
- `EngineBehaviorTest`
- `EngineErrorHandlingTest`
- `EngineHistoryTest`
- `CockpitServiceTest`

Keep H2-only/default:
- `CockpitPlaywrightTest`
- pure DSL / Mermaid / unit-only tests that do not depend on Spring JDBC

## Risks to watch

- SQL Server identifier/type differences vs H2 (`uuid` vs `uniqueidentifier`, `boolean` vs `bit`, `clob` vs `varchar(max)`).
- Query portability in `source/springDataJdbc.kt`, especially window functions and parameter binding behavior.
- Slower startup and readiness timing in CI.
- SQL Server image licensing/EULA acceptance in CI.

## Definition of done for the future implementation task

- `./gradlew test` remains fast and H2-backed.
- A separate MSSQL-backed test task exists and passes in CI.
- The MSSQL workflow runs on pull requests.
- Failures clearly point to vendor-specific persistence incompatibilities rather than browser/UI noise.