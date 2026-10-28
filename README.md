# NSI DDS client library

`net.es.nsi:dds-lib:2.0.0` is a Java client library for the NSI Document
Distribution Service. It targets Java 17 and uses `net.es.nsi:common-lib:2.0.1`.
The build and runtime compatibility matrix covers **Java 17, 21, 25, and 27**.
Other JDK versions are unverified; the library does not reject them at runtime.

## Building and testing

Use one of the supported JDKs and Maven 3.9 or later. Maven 3.10.0 and JDK 21
match the default development container. Production and test compilation use
`--release 17`, including when Maven runs on a newer JDK:

```sh
mvn --batch-mode --no-transfer-progress clean verify
```

For focused tests, use `-Dtest=ClassName test`. Tests use JUnit 4 and local HTTP/TLS
fixtures; they do not require a DDS peer. The parent binds Failsafe's
`integration-test` and `verify` goals, but no separate integration tests are
currently present. Maven skips Javadoc by default. After a successful build,
validate handwritten API documentation separately, using compiled DDS models and
Maven dependencies on the classpath:

```sh
mvn --batch-mode --no-transfer-progress dependency:build-classpath \
  -Dmdep.outputFile=target/javadoc-classpath.txt
rg --files src/main/java -g '*.java' > target/handwritten-sources.txt
javadoc -quiet -encoding UTF-8 -source 17 -Xdoclint:all,-missing -Werror \
  -classpath "target/classes:$(cat target/javadoc-classpath.txt)" \
  -d target/apidocs @target/handwritten-sources.txt
```

This checks handwritten sources without regenerating documentation for the DDS
model. It requires `rg` (ripgrep) to produce the source-file list.

The Makefile uses `maven:3.10.0-eclipse-temurin-21` and requires Docker. `make build`
runs `clean install` with tests skipped; `make test` includes tests. Both mount
the checkout and the local Maven cache. Installation is local; it does not publish
artifacts. GitHub Packages dependencies may require credentials in existing Maven
settings. Ensure `common-lib:2.0.1` is available from your configured repository or
local cache; successful resolution from a local cache does not prove publication.

## Compatibility verification

`.github/workflows/java-compatibility.yml` runs independent source builds on
Temurin 17, 21, 25, and 27 with Maven 3.10.0. Each job runs `clean verify`,
validates handwritten production/test Javadoc with doclint warnings treated as
errors, and checks the artifact and resolved dependencies for Java 17 bytecode
compatibility. Multi-release JAR checks select only entries effective for each
runtime, so optional newer entries do not invalidate Java 17 support.

A second four-JDK matrix downloads the same JAR and compiled tests built on Java
17. It restores classes/resources from that JAR into `target/classes` and compiled
tests/resources into `target/test-classes`, then invokes `surefire:test` directly
without recompilation. This verifies the Java 17 artifact on every supported
runtime, in addition to building from source with each JDK. Support is verified
when all source-build and shared-artifact runtime jobs pass; local checks on one
JDK alone do not certify the entire matrix. CI targets Linux/Temurin, not every
OS/JDK-vendor combination.

After a local `clean verify`, run the same static/documentation checks with:

```sh
mvn --batch-mode --no-transfer-progress dependency:build-classpath \
  -Dmdep.outputFile=target/compatibility-classpath.txt
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py'
python3 -B .github/scripts/compatibility.py bytecode --java 17
python3 -B .github/scripts/compatibility.py javadoc
```

The helper uses only Python's standard library. To reproduce shared-artifact
verification, run its `bundle /absolute/path/java17-runtime.zip` command after a
Java 17 build. In a clean checkout using each target JDK, run `restore` on that
archive, resolve the verification classpath, check `bytecode --java VERSION`, and
run `mvn --batch-mode --no-transfer-progress surefire:test -DfailIfNoTests=true`.
The restore command refuses populated output directories to prevent stale classes
from masking a compatibility failure.

CI deliberately avoids Maven dependency caches so package availability is checked
from a clean repository. The workflow grants only `contents: read` and
`packages: read` and uses `GITHUB_TOKEN` by default. For common-library packages
that cannot be read across repositories with that token, configure repository
secrets `PACKAGES_USERNAME` and `PACKAGES_READ_TOKEN` with the username and a
read-only package token authorized for the common-library repository. Credentials
are supplied through environment references in generated Maven settings, never
committed. A dependency-resolution failure is reported explicitly and fails CI.
Fork pull requests do not receive these secrets: if package access is unavailable,
a maintainer must review the change and run full verification on a maintainer
branch. The workflow does not use `pull_request_target` or silently mark missing
verification successful. Compatibility checks do not install or publish artifacts.

## Dependency management

The Spring Boot 4.1.2 parent manages the dependency baseline. The POM explicitly
pins selected library and plugin versions; removing those properties can change
the resolved versions. JAXB runtime follows the parent's `glassfish-jaxb.version`
property; the JAXB API uses `jakarta-xml-bind.version`.

Bouncy Castle provider/PKIX modules use `bouncycastle.version=1.86`, while TLS has
a separate `bouncycastle-tls.version=1.86.2` override. Do not apply the TLS patch
version to every BC artifact. Maven Install and Deploy plugins are pinned at
3.2.0. `clean verify` builds and tests without running either publication-related
goal; `install` writes to the local repository and `deploy` publishes remotely.

Renovate uses [`.github/renovate.json`](.github/renovate.json), following the
`nsi-dds` and `nsi-pce` policy: releases wait eight days, minor/patch updates are
grouped for automerge after required checks pass, and pin/digest updates have no
release-age delay. Branches are rebased when behind the base branch. Spring Boot
and `common-lib` updates have separate PRs that require manual merging. Major
upgrades require approval in the Dependency Dashboard before PR creation and
manual merging afterward. Use that dashboard to review pending updates, lookup
errors, and release-age holds.

The Maven-property preset reads the `renovate:` comments above version overrides
that are consumed only by the external parent. Keep those mappings with their
properties; versions referenced directly in this POM use the native Maven
manager. The Bouncy Castle provider/PKIX and TLS properties remain independent.
Java runtime updates are disabled: changes to the Java 17 baseline, the
17/21/25/27 matrix, and the fixed Maven 3.10.0/JDK 21 development toolchain must
be maintained together. The Makefile image and Maven installer are not included
in custom extraction.

Before enabling Renovate, include this repository in the organization's existing
Renovate app/runner and ensure the compatibility workflow is on the default
branch. Configure all eight source-build and shared-artifact runtime checks as
required checks, and enable repository auto-merge for platform automerge. Give
Renovate read access to the private `common-lib` package using the organization's
Renovate credential mechanism; CI's `PACKAGES_USERNAME`/`PACKAGES_READ_TOKEN`
secrets do not automatically authenticate Renovate. Keep credentials out of this
configuration. Confirm private-package lookup and release timestamps in the first
run: missing timestamps can hold updates under the release-age policy.

Validate configuration changes with a supported Renovate/Node installation:

```sh
renovate-config-validator --strict --no-global .github/renovate.json
```

Also check a read-only extraction/lookup dry run for all intended Maven properties
and GitHub Actions, without duplicate property ownership. Dependency updates must
pass the full compatibility matrix described above. See Renovate's
[Maven-property preset](https://docs.renovatebot.com/presets-customManagers/#custommanagersmavenpropertyversions),
[validation guidance](https://docs.renovatebot.com/config-validation/), and
[private-package setup](https://docs.renovatebot.com/getting-started/private-packages/).

## Migrating from 1.x

The 2.x line raises the Java minimum from 8 to 17 and changes the public Java API
to Jakarta XML Binding 4 and Jakarta REST 4, with Jersey 4 and Apache HttpClient 5.
Recompile consumers and update affected imports:

| Old type family | New type family |
| --- | --- |
| `javax.xml.bind.*` | `jakarta.xml.bind.*` |
| `javax.ws.rs.*` | `jakarta.ws.rs.*` |
| `org.apache.http.impl.conn.PoolingHttpClientConnectionManager` | `org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager` |

This affects parser exceptions and JAXB elements, result status values, client
accessors, and `RestClient.getClientConfig`. JDK-owned packages such as
`javax.net.ssl` and `javax.xml.datatype` remain unchanged. Generated DDS classes
retain their Java package, but now carry Jakarta JAXB annotations.

The DDS schema, namespaces, root QNames, and XML shape remain unchanged. Model
generation reads `src/main/schema/dds/xsd/` and `src/main/schema/dds/xjb/`; do not
edit generated Java under `target/`.

## HTTP and resource ownership

Close each `DdsClient` or `RestClient` with `close()` when finished. `DdsClient`
consumes and closes its per-call responses. Callers using `get()` or `getClient()`
directly must close their own Jakarta REST responses; the returned client belongs
to the enclosing `RestClient`.

Document and subscription reads expect `200`, subscription creation expects
`201`, and subscription deletion expects `204`. Conditional subscription reads
send `If-Modified-Since` in GMT at second precision and preserve `304` without a
payload. Result timestamps are epoch milliseconds. Recognized remote statuses
are retained when entity handling succeeds; malformed or missing required XML
and transport failures generally become `INTERNAL_SERVER_ERROR`. `delete` retains
`BAD_REQUEST` on an exception, and the bulk unsubscribe helper retains its legacy
`BAD_REQUEST` result even after processing subscriptions. Inspect result status
and payload together; an unrecognized HTTP status can produce a null status.

Jersey handles gzip negotiation and decoding. Subscription creation sends a
chunked, gzip-compressed XML request. Pool limits default to 10 connections per
route and 80 total. Timeout system properties use milliseconds:

| Property | Default |
| --- | ---: |
| `tcpConnectTimeout` | 20000 |
| `tcpSoTimeout` | 60000 |
| `tcpConnectRequestTimeout` | 30000 |

Automatic I/O retries are limited to three immediate retries of eligible,
entity-less idempotent requests. HTTP responses, including `429` and `503`, are
not retried. Compared with 1.x, some pre-send failures and non-idempotent requests
now fail without a retry. GET/HEAD may follow `301`, `302`, `307`, and `308`;
`303` uses the connector's redirect semantics. HTTPS-to-HTTP redirects are
refused, and cross-origin credential protections may leave a redirect unfollowed.

## Using results

```java
import net.es.nsi.dds.lib.client.DdsClient;
import net.es.nsi.dds.lib.client.DocumentsResult;

DdsClient client = new DdsClient();
try {
    DocumentsResult result = client.getDocuments("https://dds.example/nsi/dds");
    if (result.getStatus() == jakarta.ws.rs.core.Response.Status.OK) {
        result.getDocuments().forEach(document -> System.out.println(document.getId()));
    }
} finally {
    client.close();
}
```

Results and JAXB models are mutable. `DocumentResult`, `SubscriptionResult`, and
`SubscriptionsResult` compare/hash only their payloads, excluding inherited status
and last-modified metadata. `DocumentsResult` includes that inherited metadata;
its document list starts empty. Other payloads start null, and an absent payload
does not by itself indicate failure: successful deletion and `304` subscription
reads have no subscription entity. Payload lists and model values are exposed by
reference. Do not mutate results while using them as hash-based collection keys.

## Bulk unsubscribe destinations

`unsubscribe(baseURL, nsaId, id)` requests the service's subscription list and
retains entries whose ID matches `id` without case. Before sending any DELETE,
it validates every other entry against the caller-selected service origin and
the exact `subscriptions/{id}` resource under its deployment path. Scheme and
host comparison ignore case, omitted default ports are equivalent, and resource
IDs remain case-sensitive. DNS aliases are not treated as the same origin.

Relative links append to the service base path, including links with a leading
slash. For example, with base `https://dds.example/nsi/dds`, both
`subscriptions/item-1` and `/subscriptions/item-1` identify
`https://dds.example/nsi/dds/subscriptions/item-1`. Absolute links must identify
that same resource. Ordinary non-UUID and Unicode IDs are supported; accepted
Unicode paths are encoded before dispatch without Unicode normalization. Encoding
preserves the validated code-point sequence, so normalization-distinct IDs and
service paths remain distinct. The retained-ID filter still compares without case.

Bulk cleanup rejects cross-origin links, other resource paths, mismatched IDs,
userinfo, query strings, fragments, traversal, matrix parameters, malformed
escapes, and encoded or double-encoded separators. IDs containing whitespace,
percent signs, or path delimiters are unsupported even though the XML schema
allows arbitrary strings. Deployments using those forms or alternate service
origins must supply canonical links or use deliberate direct operations.

The list GET and bulk DELETEs do not follow redirects, and this request-local
policy does not change the shared client configuration. The list response closes
before validation and deletion. If listing or validation fails, the result is
`BAD_REQUEST` with a null subscription list; an invalid selected entry prevents
all DELETEs. A null retained ID returns the same result without a request.

After processing, the result contains the selected entries in their original
order without rewriting their `href` values. The aggregate status retains its
legacy `BAD_REQUEST` value, and individual deletion outcomes are not propagated.
Network failures after deletion begins do not roll back earlier requests. Target
validation limits destinations; it does not establish authorization for the
peer's listed resources.

The two-argument `unsubscribe(baseURL, url)` and `delete(url)` methods retain
caller-selected destinations and the existing redirect policy. They do not apply
the bulk target restrictions.

## XML, signatures, and TLS

`DdsParser` shares a JAXB context for DDS and common-library NSA, NML, and
signature models. Stream parsing leaves caller-owned streams open; file methods
close streams they open. The resolved common-library parser rejects DTDs and
external entities. Parsing is not a substitute for validating application data.

`DocumentBuilder` stores XML content as gzip bytes with Base64 transfer encoding.
Decode transfer encoding before decompression. `verify` accepts unsigned
documents and empty signatures; nonempty signatures must validate or it throws
`SecurityException`. `build` logs a failed signature check but still includes the
provided signature, so it is not a validation gate. Neither successful decoding
nor signature verification establishes certificate trust or signer authorization.

TLS clients use configured key/truststore paths, types, and passwords, with the
existing JSSE property fallback when stores are omitted. Relative paths resolve
against the configured application base directory. Set `SecureType.production`
to `true` for hostname verification; its default `false` permits hostname
mismatches and is intended for nonproduction use. Certificate trust checks remain
enabled in both modes. Without explicit `SecureType` configuration, the connector
uses its own TLS defaults; this does not disable HTTPS verification.

`HttpsContext` is process-wide and updates the default SSL context when loaded;
coordinate direct access to this shared state. Loading also populates and resolves
the supplied store configuration. Bouncy Castle providers are registered when
absent, but the TLS context uses normal provider selection rather than forcing
BCJSSE. Configuration objects retain passwords, and their generated string
representations include them. Keep those objects out of logs; enabling HTTP
payload logging can also expose document contents.

Content parsing builds a DOM, and the library does not impose a general decoded
size limit. Enforce application-appropriate input limits when accepting untrusted
or highly compressed envelopes.
