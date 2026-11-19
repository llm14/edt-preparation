# Tasks

## 1. Spike: MDC fields in workflow code

- [x] 1.1 In a throw-away test in the orchestrator (not committed), run the Flow or Service Request workflow with `TestWorkflowEnvironment`, wrap one workflow log call in `LogFields`, and capture the log records. Verify: the wrapped line's MDC holds the fields, the next unwrapped line in the same workflow does not, and no field is left in the MDC after the workflow finishes.
- [x] 1.2 Record the result in design.md (decision 4 and the workflow-test choice in decision 5): whether the test classpath routes SLF4J to the JBoss log manager, which capture technique works, and whether the fields leak. Verify: design.md states the outcome. **If the fields leak or cannot be captured in a unit test, stop and ask before groups 4 and 5 go on** (the fallback is in the design's Risks).

## 2. Application fields at the top level (scope A)

- [x] 2.1 In the orchestrator, add `LoggingConfigTest` (plain JUnit) that reads the app's own `application.properties` and asserts `log-format=ecs`, `mdc.flat-fields=true`, and that JSON stays off in the dev and test profiles. Run it first and watch it fail, then add `quarkus.log.console.json.mdc.flat-fields=true` next to `log-format=ecs`. Verify: `./gradlew :orchestrator:test --tests '*LoggingConfigTest'` fails before the property and passes after.
- [x] 2.2 Do the same for Batch Ingest, DUIS Signer and Response Listener (one test class and one property line each). Verify: `./gradlew test` passes in each of the three apps, and each test fails if the property is removed.
- [x] 2.3 Update README Step 4: the two examples that show the nested `mdc`, the sentence "Custom fields appear under `mdc`", and the rules (fields at the top level, a key at most once, `error.message` and `error.type` from one source per line). Verify: no `mdc` example is left in Step 4 or in the README's other log examples, and the examples match a real line from the cluster in group 5.

## 3. A check of the real log shape

- [x] 3.1 Write `deploy/check-log-shape.sh`: it reads `kubectl logs` of the four apps and fails if a JSON line does not parse, lacks `@timestamp`, `log.level`, `message`, `service.name` or `ecs.version`, has an `mdc` object, or repeats a key (found while parsing); it prints the app and the first offending line. Run it against the cluster as it is now (old nested shape). Verify: it exits non-zero and reports `mdc` for the apps that have logged fields.
- [x] 3.2 Document the script in README Step 4 (what it checks, that it needs the cluster running, and that it is the check to run after any change to logging). Verify: the documented command runs as written.

- [ ] 3.3 Add `--enable-native-access=ALL-UNNAMED` to the `ENTRYPOINT` of the four Dockerfiles (design decision 6) and mention it in the README's Dockerfile row. Verify: after `deploy/edt.sh up` (group 5), the first line of each app's log is JSON and `deploy/check-log-shape.sh` reports no "not JSON" lines.

## 4. Workflow lines carry their data as fields (scope B)

- [ ] 4.1 Batch and Page workflows: write the three lines as in design decision 4 (`LogFields` around the log call only, constant message, `event.action` and the listed fields). Add a test that runs the workflow in memory and asserts the fields and the constant message. Verify: the new test passes and `BatchWorkflowTest` still passes.
- [ ] 4.2 Flow and Service Request workflows: the same for `Flow ended`, `DCC Alert received, still waiting` and `Service Request ended`. Verify: the new tests pass, and `PlaceholderFlowWorkflowTest` and `ServiceRequestWorkflowTest` still pass.
- [ ] 4.3 Sign probe workflow: the same for its two lines (`edt.refusal_reason` only when there is one). Verify: the new test passes and `SignProbeWorkflowTest` still passes.
- [ ] 4.4 Update `HAPPY-PATH-MANUAL.md` line 43 to the new message and its fields. Verify: the quoted text matches the real line seen in group 5.

## 5. Check on the cluster

- [ ] 5.1 Build and install (`deploy/edt.sh up`), then run the one-device sample Manifest (as in the manual's cluster note) and a device that sends a DCC Alert before its Response (`...-A5`). Verify: `deploy/check-log-shape.sh` exits 0 for all four apps, and the lines for `Batch ended`, `Flow ended`, `Service Request ended` and `DCC Alert received, still waiting` show top-level fields and no `mdc`.
- [ ] 5.2 Replay check: upload a one-device Manifest for `...-A3` (a device the simulator never answers, so its Service Request waits 2 minutes), restart the Orchestrator during the wait, and let it finish. Verify: each workflow log line appears once per real event in the Orchestrator logs (no line written twice by the replay), and `deploy/check-log-shape.sh` still exits 0.

## Workflow follow-up

- Ask DCC what its log agent and CloudWatch queries expect (top-level fields or nested `mdc`) before the change goes beyond local use; update the [CONFIRM] in the spec when answered.
- Archive the change after review so `specs/structured-logging/` becomes a main spec.
