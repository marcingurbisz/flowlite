## [IN PROGRESS 2026-03-07] Showcase improvements
* Describe our appoach to showcase application in README E.g. how Seeder works
* Move ShowcaseActionBehavior to testApplication.kt
* isRemoteEmployee used for isShowcaseInstance - maybe dedicated/explicite attribute for this in EmployeeOnboarding?

## Move tables definitions outside testApplication.kt 
Put them into seperate file or files? Prepare for more db to suppport but now only provide scripts for h2 and mssql

## Prepare idea for runnig test on mssql too

## Refactor DSL to procedural style

Implement only support for dsl that you see in these two examples below. Do not care about braking changes :). Update source and test leaving only what is needed after this dsl change.

Change order confirmation to this:

fun createOrderConfirmationFlow() =
    flow<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent> {
        stage(InitializingConfirmation, ::initializeOrderConfirmation)
        stage(WaitingForConfirmation, waitFor = Confirmed)
        _if(::wasConfirmedDigitally) {
            stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
        }
        stage(InformingCustomer, ::informCustomer)
    }

Replace employee onbording with this:

fun createEmployeeOnboardingFlow(actions: EmployeeOnboardingActions) =
    flow<EmployeeOnboarding, EmployeeOnboardingStage, EmployeeOnboardingEvent> {

        _if(::isOnboardingAutomated) {
            stage(CreateEmployeeProfile, actions::createEmployeeProfile)

            _if(::needsTrainingProgram) {

                _if(::isEngineeringRole) {
                    stage(ActivateSystemAccess, actions::activateSystemAccess)
                    timer(WaitForITBusinessHours, actions::effectiveITWorkingDateTime)
                    stage(CreateAccountsInExternalSystems, actions::createAccountsInExternalSystems)
                    stage(UpdateBenefitsEnrollment, actions::updateBenefitsEnrollment)
                } _else {
                    _if(::isFullSecuritySetup) {
                        stage(SetSecurityClearanceLevels, actions::setSecurityClearanceLevels)
                    }
                }

                stage(GenerateOnboardingDocuments, actions::generateOnboardingDocuments)
                stage(SendContractForSigning, actions::sendContractForSigning)
                stage(WaitingForContractSigned, waitFor = ContractSigned)

                _if(::wereDocumentsSignedPhysically) {
                    stage(RemoveFromSigningQueue, actions::removeFromSigningQueue)
                }
            }
        }

        stage(WaitingForOnboardingAgreementSigned, waitFor = OnboardingAgreementSigned)
        timer(Delay5Min, actions::delay5Min)

        _if(::isNotManualPath) {
            _if(::isExecutiveOrManagement) {
                stage(ActivateSpecializedAccess, actions::activateSpecializedAccess)
            } _else {
                _if(::hasComplianceChecks) {
                    stage(WaitingForComplianceComplete, waitFor = ComplianceComplete)
                }
            }

            stage(UpdateHRSystem, actions::updateHRSystem)
            timer(DelayAfterHRUpdate, actions::delayAfterHRUpdate)
        } _else {
            stage(WaitingForManualApproval, waitFor = ManualApproval)
            stage(FetchEmployeeRecords, actions::fetchEmployeeRecords)
        }

        _if(::isNotContractor) {
            stage(UpdateDepartmentAssignment, actions::updateDepartmentAssignment)
            stage(LinkToOrganizationChart, actions::linkToOrganizationChart)
        }

        stage(UpdateStatusInPayroll, actions::updateStatusInPayroll)
        stage(CompleteOnboarding, actions::completeOnboarding)
    }

## Playwright: use date and timestap in artifact names instead toEpochMilli 

## More playwright tests
Implement tests from [PlaywrightTestScenarios.md](PlaywrightTestScenarios.md). In addition, test:
* From Flow Definitions > concrete flow:
  * Going from flow definition overview to long running instances
  * Going to incomplete instances
  * Going to instances active in given stage
  * Going to instances in error in given stage
* Error tab:
  * List is empty at first
  * Filter by flow
  * Filter by stage
  * Filter by error message
  * Stack trace text is available after expanding it
  * Selecting/deselecting
  * Retry, changing stage and canceling selected
* Long running tab:
  * flow definition filter works
  * Threshold filter works
  * select, deselect, retry selected works
* Instances tab:
  * Search by instance id and flow id
  * filter by stage
  * filter by error message
  * filter by status
  * clear filter works
* Back button support
* All views are bookmarkable, links include tab and applied filters

## [IN PROGRESS] Expose test instance publicly available - part 4
Start app in docker without gradle using springboot jar?
Pack cockpit-ui into jar
* use only dist in CockpitUiStaticConfig
* Deploy to render

## [WAITING FOR BETTER SPEC] Duplicate copkpit but in Kotlin 
Create a duplicate of cockpit-ui but written in Kotlin (cockpit-ui-kotlin)

## [DONE 2026-03-06] Failed on step Test + Coverage
See https://github.com/marcingurbisz/flowlite/actions/runs/22705767837/job/65832358931

Completed changes:
- Identified clean-checkout failure mode: `cockpit-ui/dist` was not versioned and not built before `CockpitPlaywrightTest`.
- Added Gradle task `installCockpitUiDeps` (`npm ci`) and `buildCockpitUi` (`npm run build`) in `build.gradle.kts`.
- Wired `tasks.test` to depend on `buildCockpitUi`, making CI/local test runs deterministic.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test jacocoTestReport` → BUILD SUCCESSFUL.

## [DONE 2026-03-06] Why we need both private val distPath: Path = Paths.get("cockpit-ui", "dist") ?

Decision:
- Keep both derived locations in `CockpitUiStaticConfig`: `distLocation` for HTML/root files and `distAssetsLocation` for `/assets/**`.

Rationale:
- A simplification attempt that mapped `/assets/**` to `distLocation` caused `CockpitPlaywrightTest` to fail (UI heading not rendered due missing static asset resolution).
- `distAssetsLocation` is therefore required for correct `/assets/**` serving in current setup.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL (after reverting simplification).

## [DONE 2026-03-06] Playwright test improvements
Completed changes:
- Improved Kotlin Playwright artifacts in `test/CockpitPlaywrightTest.kt`: screenshots and videos now use `<test-name>-<timestamp>` naming.
- Added stable `data-testid` selectors in `cockpit-ui/src/App.tsx` for cockpit title, tabs, flow cards/actions, instances search/rows, instance details, and flow diagram modal.
- Expanded Kotlin Playwright coverage with new scenarios:
	- open/close flow diagram modal from a flow card,
	- jump from flow card to instances view with pre-filled search.
- Added scenario planning document: `memory/PlaywrightTestScenarios.md`.
- Removed legacy TypeScript Playwright setup:
	- deleted `cockpit-ui/playwright.config.ts`,
	- deleted `cockpit-ui/tests/cockpit.spec.ts`,
	- removed Playwright scripts/dependency from `cockpit-ui/package.json` and refreshed lockfile.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-06] Expose test instance publicly available - part 3
Completed changes:
- Added scheduled Render keepalive workflow: `.github/workflows/keep-render-alive.yml` (uses repository variable `FLOWLITE_RENDER_URL`).
- Updated `README.md` deployment chapter to pure step-by-step instructions (removed provider-comparison narrative).
- Removed obsolete local tunnel script: `tools/exposeTestInstance.sh`.

Decision:
- Keep runtime command on `./gradlew runTestApp` for now; moving to a Spring Boot jar is deferred because current public test app is test-source-set based and has no dedicated production-style bootJar pipeline yet.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-06] Improve showcase mode
Completed changes:
- Added showcase behavior helper `test/showcaseActionBehavior.kt` that applies randomized action delay (`0..maxDelayMs`) and probabilistic failures for showcase-marked instances.
- Wired showcase config in `ShowcaseFlowSeeder` with new properties:
	- `flowlite.showcase.max-action-delay-ms` (default `60000`),
	- `flowlite.showcase.action-failure-rate` (default `0.1`).
- Marked showcase-seeded employee instances via `isRemoteEmployee = true` and applied behavior in all employee onboarding actions.
- Applied showcase behavior in order-confirmation actions for `orderNumber` prefixed with `SHOW-`.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.