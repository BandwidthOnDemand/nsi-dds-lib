# Supplemental repository context

Root AGENTS.md contains the maintained repository requirements and complete code
review rubric. Keep build, compatibility, HTTP/TLS, XML, testing, and documentation
requirements there rather than duplicating them here.

Use this file for additional facts that help locate work. Verify them against the
current source and POM; unknown entries are not new requirements. If observed code
contradicts an explicit AGENTS.md contract, report the discrepancy rather than
silently redefining it here.

- Library entry point: `src/main/java/net/es/nsi/dds/lib/client/DdsClient.java`.
  It extends `RestClient` and returns `Result`, `DocumentResult`, `DocumentsResult`,
  `SubscriptionResult`, and `SubscriptionsResult` from the same package.
- HTTP transport: `client/RestClient.java` configures Jersey's Apache5 connector,
  connection pooling, request timeouts, and explicit redirect/retry strategies.
- TLS entry points: `dao/HttpsContext.java`, `dao/SecureType.java`, and
  `dao/KeyStoreType.java`. Configuration properties and defaults also come from
  `net.es.nsi.common.constants.Properties` in the resolved common dependency.
- XML facade: `jaxb/DdsParser.java` extends the common library's `JaxbParser`.
  Document payload construction and optional signature checks are in
  `util/DocumentBuilder.java`. Java paths above are relative to
  `src/main/java/net/es/nsi/dds/lib/` unless fully specified.
- Schema entry: `src/main/schema/dds/xsd/ogf_nsi_discovery_protocol_v1_0.xsd`.
  Binding customization: `src/main/schema/dds/xjb/dds.xjb`.
  Generator configuration: the `jaxb2-maven-plugin` execution named `dds` in
  `pom.xml`.
- Test entry points under `src/test/java/net/es/nsi/dds/lib/` include
  `client/*MigrationTest.java`, `client/RestClientRetryTest.java`,
  `jaxb/DdsParserMigrationTest.java`, and `util/DocumentBuilderMigrationTest.java`.
  `util/XmlUtilitiesTest.java` exercises common-library calendar comparisons.
  Shared disposable key/truststore creation is in `test/KeytoolFixture.java`.
- Representative callers: not yet established. Inspect a consuming project's
  declared dependency coordinates and imports before using its behavior as a
  compatibility requirement.
- CI: no CI workflow configuration is present in this checkout at the time this
  context was added. Inspect the current tree before relying on that observation.
- Additional API specifications, module checks, and architectural decision records:
  discover from the task and current tree; none are established by this file.

Model choices, reasoning effort, and the agent concurrency cap are configured in
`.codex/config.toml` and `.codex/agents/*.toml`, not in this supplemental context.
