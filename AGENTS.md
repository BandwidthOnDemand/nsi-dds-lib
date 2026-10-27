# AGENTS.md

## Repository-Specific Rules

These rules apply to implementation and review in this checkout. The code review
rules below add requirements when the user requests a review.

### Layout and style

- This is the single-module `net.es.nsi:dds-lib` Java client library for the NSI
  Document Distribution Service, not the DDS server or its deployment repository.
- Handwritten code lives under `src/main/java/net/es/nsi/dds/lib/`: `client/`
  contains HTTP clients and result wrappers, `dao/` contains TLS configuration,
  `jaxb/` contains the parser facade, and `util/` contains document construction.
  Tests mirror packages under `src/test/java/`; schemas live in `src/main/schema/`.
- Follow workspace Java naming and four-space indentation for new code; preserve
  surrounding conventions and avoid unrelated reformatting. Keep UTF-8, LF endings,
  and final newlines. No repository formatter or lint task is currently configured.

### Build and dependencies

- The POM targets Java 21 with compiler `release` 21. Preserve Java 21 language
  and API compatibility; compiling on a newer JDK alone does not establish that
  compatibility. Check common-library behavior against the resolved dependency,
  not the sibling checkout.
- Run focused tests with
  `mvn --batch-mode --no-transfer-progress -Dtest=ClassName test`, then run
  `mvn --batch-mode --no-transfer-progress clean verify` for final verification of
  Java, schema, or build changes. Add `-o` only when all required artifacts are cached.
- `make build` runs `clean install` with tests skipped. `make test` runs
  `clean install` with tests. Both use the fixed `maven:3.10.0-eclipse-temurin-21`
  Docker image and mount the checkout and local Maven cache; there is no
  `MAVEN_IMAGE` override. Inspect Docker prerequisites before using these targets.
- The Spring Boot 4.1.2 parent manages dependencies; the direct common library is
  `net.es.nsi:common-lib:2.0.0`. Check compatibility against that resolved artifact,
  not the sibling checkout's APIs or instructions. Upgrades are separate work
  unless explicitly in scope. Preserve public signatures and checked exceptions.
- Prefer fixed stable versions for dependency changes and existing parent/property
  management. Successful compilation alone is insufficient
  evidence of compatibility after a dependency upgrade.
- Some artifacts resolve through GitHub Packages. Use existing Maven settings and
  report missing access or cached artifacts; never commit credentials or copy them
  into agent configuration. Installing locally is distinct from publishing.

### JAXB, XML, and document encoding

- Edit `src/main/schema/dds/xsd/` and `src/main/schema/dds/xjb/` for DDS model
  changes. Maven XJC generates `net.es.nsi.dds.lib.jaxb.dds` under `target/`.
  Do not hand-edit generated Java files or their documentation. Preserve schema
  namespaces, root QNames, and compatibility with existing DDS documents.
- This library uses Jakarta XML Binding 4 (`jakarta.xml.bind.*`) and
  Jersey 4/Jakarta REST 4 (`jakarta.ws.rs.*`). Preserve these API contracts until
  an explicitly scoped migration. JDK-owned `javax` APIs remain unchanged.
  `DdsParser` also loads NSA, NML, and signature models from the common dependency.
- Reuse JAXB contexts while keeping marshaller/unmarshaller state local to an
  operation. Preserve parser input/output contracts and caller-owned stream
  ownership; methods that open files must close their own streams.
- New runtime XML parsers must be namespace-aware and disable DTDs and external
  entities. The build's XJC external-DTD setting is not a runtime security policy.
  Inspect the resolved common-library implementation before claiming that existing
  parsing paths provide protections present only in its newer sibling checkout.
- Preserve document metadata, Unicode, and content/transfer-encoding semantics.
  `DocumentBuilder` uses gzip content and Base64 transfer encoding through common
  helpers. Decode transfer encoding before decompression; avoid double processing.
- `DocumentBuilder.verify` accepts unsigned documents; it validates signatures
  only when their content values are nonempty. Decoding or successful signature
  validation does not establish certificate trust or signer authorization. Document
  that boundary; do not assume newer common-library algorithms or structures apply.

### HTTP, TLS, and resource ownership

- Preserve `DdsClient` HTTP status mappings, result payloads, headers, conditional
  request behavior, and error contracts. Test success, empty/error responses, and
  malformed entities when changing them; do not turn a remote failure into success.
- Close per-call JAX-RS responses on every path after consuming needed entities.
  Callers own long-lived clients and close them through `RestClient.close()`.
  Do not close a shared client per request or leak pooled connections on failures.
- Keep connection-pool bounds and connect, socket, and pool-acquisition timeouts
  explicit. Redirect changes must account for target resolution, method/body
  replay, response cleanup, loops, credential forwarding, and HTTPS downgrades.
- Keep production certificate and hostname verification enabled. `SecureType`'s
  production flag selects hostname verification; its nonproduction mode uses a
  permissive verifier. Do not enable that mode as a fix for production TLS errors.
- Preserve keystore/truststore paths, types, passwords, and JSSE-property fallback
  behavior when maintaining configuration. Never log passwords or private keys.
  `HttpsContext` is a process-wide singleton; account for shared state and security
  provider registration when changing initialization or testing concurrent clients.

### Regression tests and documentation

- Use the existing JUnit 4 (`org.junit.*`) stack, matching source packages and
  `*Test.java` naming. Add focused regressions for consequential behavior changes
  and confirm they expose the original defect when practical. Do not introduce a
  JUnit 5 migration as an incidental test change.
- Keep tests independent of production DDS peers. Use local HTTP/TLS fixtures with
  bounded timeouts and reliable shutdown. Generate disposable key/truststores with
  the running JDK's `keytool`; use JUnit temporary directories for test files.
- Serialize tests that alter system properties, security providers, the TLS
  singleton, or the default time zone. Restore prior global state, including
  previously absent properties, and close clients, responses, servers, and streams.
- No numeric coverage gate is configured here. The parent binds Failsafe's
  `integration-test` and `verify` goals, but no separate integration tests are
  currently present. Do not claim that unit-test counts include an integration
  suite or import common-lib's coverage gates and test helpers.
- Maintain handwritten API Javadoc and package documentation for inputs, outputs,
  errors, ownership, encoding, and security boundaries. Maven skips Javadoc by
  default. For handwritten Javadoc changes, validate it separately with JDK
  Javadoc over handwritten sources, compiled classes and Maven dependencies on the
  classpath, and `-Xdoclint:all,-missing -Werror`.
- For Markdown or `.codex`-only changes, verify referenced paths and facts, parse
  changed TOML, and check whitespace; a full Maven build is unnecessary. Finish
  changes with `git diff --check` and inspect new, untracked files as well.

## Agent Coordination

### Shared rules and task ownership

- The Repository-Specific Rules above apply to every agent. Custom agent files
  select a role, model, and reasoning setting; they do not relax these contracts
  or replace the required verification. Apply the Code Review Rules below when
  the assigned task is a review.
- Keep repository requirements and the review rubric in this AGENTS.md as their
  single maintained source. Use .codex/PROJECT_CONTEXT.md only for supplemental
  facts that are not already specified here.
- If implementation, supplemental context, or generic role guidance conflicts
  with an explicit repository rule, identify the discrepancy; do not silently
  redefine the rule to match the implementation.
- Respect the user's task scope and preserve unrelated working-tree changes.
  Inspect the working tree before editing. Do not commit, push, publish, deploy,
  or change shared systems unless the user's task authorizes that action.

### Agent routing

Custom agents live in .codex/agents/. For a named specialist request, delegate to
that custom agent so its configured model and reasoning effort apply. Otherwise,
use only the specialists the task needs; do not invoke the whole roster by default.

| Agent | Assignment |
| --- | --- |
| planner | Architecture, migrations, and implementation plans |
| java_coder | Java implementation, bug fixes, and focused regression tests |
| reviewer | Strict correctness, concurrency, reliability, and compatibility review |
| documenter | Handwritten Javadoc, package overviews, README, and API documentation |
| test_engineer | Unit, integration, regression, and failure-path tests |
| debugger | Root-cause analysis and reproducible failure investigation |
| security_reviewer | TLS, redirects, XML/signature security, and credential boundaries |
| performance_analyst | HTTP pooling, parsing, memory, latency, and scaling analysis |
| refactorer | Scoped behavior-preserving Java refactoring |
| build_engineer | Maven, dependency management, generated sources, and CI |

### Coordination and verification

- Give delegated tasks a concrete outcome, scope, relevant contracts, and file
  ownership. Pass review baselines and acceptance criteria explicitly.
- Run writers sequentially in a shared checkout. Parallel writers require isolated
  worktrees and an integration owner. Parallel readers must not race build output,
  generated sources, local HTTP/TLS fixtures, or shared JVM state.
- Serialize Maven clean/build/verification and Javadoc commands in one checkout.
  Assign one verification owner to run the required final checks after all edits
  have settled. For Java or build changes, focused worker checks do not replace
  final Maven verification.
- For handwritten Javadoc changes, include the separate JDK Javadoc validation
  required above. For Markdown or agent configuration changes, use the applicable
  documentation/configuration checks instead of an unrelated Maven build.
- Make local HTTP/TLS fixture ownership and shared-property/provider/time-zone
  isolation explicit when assigning relevant tests.
- Settle implementation edits before reviewing a snapshot. Reassess affected
  findings and checks if code changes after review.
- Child agents return scoped results without further delegation unless the parent
  explicitly assigns it. The primary agent integrates results and deduplicates
  findings while remaining responsible for the final result.
- A review or diagnosis task does not authorize fixes. Implementation already
  authorized by the user does not need another approval gate after planning.
- Report the changed behavior, affected files, checks actually run and their
  results, and remaining limitations. Distinguish observed results from static
  reasoning, and do not claim unavailable checks passed.

## Code Review Rules

Apply these rules when asked to review Java code. These are review instructions;
they do not turn implementation tasks into review-only tasks.

### Review standard

- Be rigorous, skeptical, and direct. Prioritize correctness, security, data
  integrity, reliability, and compatibility. Critique code and explain consequences.
- Report every distinct, actionable defect supported by evidence within the
  requested scope. Do not stop after the first finding or impose a findings quota.
- For each finding, establish a reachable trigger, the faulty behavior, and the
  practical impact. A defensible code-path analysis is sufficient; execution is
  not required when the defect follows clearly from the code.
- Investigate plausible concerns before reporting them. Check callers, guards,
  contracts, framework behavior, and configuration that might invalidate a claim.
- Separate unresolved questions from defects. Do not present hypothetical callers,
  unknown requirements, or unsupported workload assumptions as established facts.
- Exclude personal preferences, generic best-practice advice, and mechanical style
  issues unless they violate an explicit requirement or cause a concrete problem.
- Do not invent issues, inflate severity, or equate unfamiliar code with bad code.

### Scope and method

- Establish the review target and baseline. For a branch review, identify the base,
  merge base, and reviewed head; state whether uncommitted changes are included.
  If the baseline is ambiguous, resolve it from context or ask before comparing.
- For change reviews, report defects introduced, exposed, or materially worsened
  by the change. Keep unrelated pre-existing issues out of the findings. For an
  explicitly requested full audit, evaluate all code in the requested scope.
- Read applicable repository instructions and relevant build files, CI settings,
  documentation, tests, callers, and implementations. Trace changed behavior
  across class and module boundaries where needed to evaluate its consequences.
- Identify the actual Java version and relevant dependency/framework versions.
  Verify version-sensitive behavior rather than assuming the newest semantics.
- Examine normal paths, boundaries, failures, concurrency, and lifecycle behavior
  where applicable. After analysis, make a final pass to remove false positives
  and merge findings that share the same root cause.
- Review only: do not edit tracked source, tests, configuration, dependencies, or
  repository instructions unless the user separately requests fixes.

### Correctness and Java contracts

- Trace nullability from real inputs and callers, including nullable return values
  and unboxing. Do not demand redundant null checks where contracts exclude null.
- Check boundary values, empty collections, overflow, truncation, numeric precision,
  parsing, character encoding, locale, time zones, and daylight-saving transitions.
- Check reference versus value equality, equals/hashCode consistency, comparator
  contracts, mutable map keys, collection mutation, aliasing, and defensive copies.
- Verify validation occurs before side effects and that partial failure cannot
  silently leave invalid state. Check ordering, duplicate requests, and idempotency.
- Check API, exception, serialization, schema, and configuration compatibility,
  including existing clients and mixed-version deployments when supported.

### Concurrency and asynchronous execution

- Establish which objects and state are actually shared and which threads access
  them. Check data races, visibility, safe publication, and compound operations.
- Do not assume volatile fields or concurrent collections make multi-step actions
  atomic. Inspect invariants that span multiple fields, keys, or operations.
- Check lock ordering, deadlock paths, lock scope, and blocking while holding locks.
- Inspect executor and queue bounds, starvation, backpressure, task rejection,
  asynchronous exception propagation, and dropped or unobserved failures.
- Trace timeout, interruption, cancellation, and shutdown behavior. Check whether
  interrupted execution exits or restores interrupt status as the contract requires.
- Check ThreadLocal and request-context cleanup across pooled or asynchronous work.
  Do not assume a singleton, framework bean, or parallel stream is thread-safe.

### Resources, failures, and operations

- Trace ownership and cleanup for files, streams, sockets, JDBC objects, HTTP
  responses, executors, and other resources on success and failure. Do not require
  code to close resources owned by its caller or managed by the framework.
- Check swallowed exceptions, misleading success responses, lost causes, and error
  handling that hides failed or partially completed operations.
- Inspect connection and request timeouts, retry bounds, backoff, and idempotency.
  Check whether retries can duplicate effects or amplify an existing outage.
- Inspect shutdown and startup paths for unfinished work, leaked resources, and
  incorrect health signals. Flag missing diagnostics only when they prevent a
  specific failure from being detected or investigated.

### Security and data boundaries

- Trace untrusted inputs through authorization decisions and sensitive operations.
  Check object-level access controls and cross-user or cross-tenant data exposure.
- Check injection, unsafe deserialization, path traversal, SSRF, XML external
  entities, and unsafe process execution where the code exposes those surfaces.
- Check TLS certificate and hostname verification, credential handling, and secret
  or sensitive-data exposure in logs, errors, telemetry, and serialized responses.
- Check whether request size, decompression, parsing, regular expressions, or other
  attacker-controlled work can exhaust memory, CPU, threads, or connections.
- Demonstrate the relevant input path and missing protection. Do not report a
  vulnerability solely because a potentially dangerous API appears in the code.

### Persistence and frameworks, when applicable

- Verify transaction boundaries, rollback behavior, isolation assumptions, and
  consistency between database changes and external effects.
- Check uniqueness and locking assumptions, lost updates, concurrent writes,
  pagination correctness, and duplicate processing after retries or redelivery.
- For Spring/JPA/Hibernate or similar frameworks, inspect relevant proxy behavior,
  annotation applicability, entity identity, lazy loading, and query behavior.
  Base findings on the configured framework version and actual execution path.
- Inspect migrations and persisted formats for data loss, deployment ordering, and
  backward compatibility where mixed versions or rollback must be supported.

### Performance and retained memory

- Look for reachable N+1 queries, repeated remote calls, avoidable quadratic work,
  unbounded result materialization, caches, queues, listeners, and retained objects.
- Connect each finding to a credible input size, request rate, or lifecycle. State
  assumptions. Do not claim a measured regression without measurements.
- Avoid speculative micro-optimization and blanket bans on language features.

### Verification

- Use the repository's documented build wrapper and checks. Run relevant existing
  tests and configured static analysis where the environment supports them.
- Inspect commands before execution; do not run checks that deploy, modify shared
  systems, or require production access as part of an ordinary code review.
- Do not install tools, change dependencies, or alter configuration just to review.
  Report missing prerequisites and checks that could not be completed.
- Evaluate whether tests exercise the changed behavior and its significant failure
  paths. For each defect, suggest a focused regression test or reproduction.
- Report missing tests as a separate finding only when an explicit testing rule is
  violated or a concrete, consequential behavior lacks required coverage. Avoid
  duplicating a bug finding with a generic request to add tests.
- Distinguish observed test results from static reasoning. Never claim a check ran
  or passed when it did not. Passing tests do not disprove a supported defect.

### Findings and priority

- Order findings by impact and urgency. Use the following review rubric:
  - P0: Critical, broadly applicable failure requiring immediate action, such as
    unavoidable data destruction or complete service failure on a normal path.
  - P1: High-impact defect to fix urgently, such as a reachable security breach,
    serious data corruption, deadlock, or major supported-workflow failure.
  - P2: Other actionable correctness, reliability, compatibility, or demonstrated
    performance defects that should be fixed in the normal development cycle.
  - P3: Low-impact improvements; include only when the user requests suggestions.
- Priority reflects impact and urgency; confidence reflects evidence. Never raise
  priority to make an issue appear in an integration that filters lower priorities.
- For a general review task, include supported P0, P1, and P2 findings. If a review
  integration constrains severity or output format, follow that interface without
  relabeling findings or claiming filtered issues were fully reported.
- Each finding must include a short severity-prefixed title, the precise file and
  smallest useful line range, triggering conditions, impact, and supporting
  reasoning. Include a minimal correction direction and regression-test scenario
  where the interface permits. Avoid unsolicited rewrites.
- Lead with findings. Then briefly report checks performed, checks not run and why,
  remaining material questions, and coverage limitations where the interface permits.
- If no actionable defects are found, say so. Do not claim the code is defect-free,
  secure, or ready to merge solely because the review produced no findings.
