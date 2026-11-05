# Private snapshot and dependency upgrade preparation

Status on 2026-10-22: technical dependency remediation completed locally; public visibility/publication still requires separate owner approval. Repository stays private. No service deployment, public Release, image/package publication or SSH connection occurred.

## Source and history

This tree is an independent open-source snapshot derived from a private predecessor. The predecessor repository and its database were not modified, and their history is not imported here.

The initial export retained401 source files and excluded28 old process/media entries:five task logs,three live-result JSONs,18 screenshots,two Playwright result JSONs. pulse-demo.webm was absent from the tracked baseline. None were restored. Browser QA uses only a disposable loopback instance with synthetic data; screenshots remain ignored local output.

## Exact versions and compatibility decisions

| Component | Final version / decision |
|---|---|
| Node |24.22.1 LTS; engines24.x minimum24.22.1, .nvmrc, CI, Docker and Node 24 type definitions aligned |
| Vite / React plugin |6.4.4 /4.7.0; supported security-backport line, peer ranges verified |
| Vitest / mocker |4.1.12; uses the same compatible Vite 6.4.4 after deduplication; tinypool removed |
| Tailwind / Vite plugin |4.3.0 /4.3.0; existing JS design tokens/theme retained via @config; obsolete PostCSS integration removed |
| Frontend dependencies |react-remove-scroll-bar2.3.7 (compatible exact-source license pin); esbuild0.25.12; braces and postcss-selector-parser removed with the old Tailwind chain, no forced transitive-major override |
| UI icons |lucide-react1.61.0; official ISC plus Feather-derived MIT notices retained, 31 old paths removed |
| Java / Spring Boot / Framework |Java 21 / Boot 4.0.8 / Framework7.0.9 |
| Servlet container |Tomcat 11.0.27 / Servlet 6.1; old10.x override removed; patched11.x overrides Boot's11.0.24 |
| JSON |Boot 4's documented deprecated Jackson 2 bridge2.21.8 selected explicitly for HTTP; Jackson 3 BOM patched to3.1.8 as well |
| Flyway |Boot 4 modular Flyway starter, Flyway 11.14.1; H2 and PostgreSQL migrations tested |

The JSON bridge preserves existing `com.fasterxml.jackson` APIs and canonical audit/evidence bytes. `spring.http.converters.preferred-json-mapper=jackson2` and `spring.jackson2` are explicit; no blind retention of the old Jackson 3 BOM property. A future Jackson 3 application migration is still required before Boot removes this deprecated bridge. New HTTP JSON/null/canonical-byte and SSE flush tests cover this choice.

Boot 4 test-module changes include explicit TestRestTemplate auto-configuration only on Web tests, updated imports, MockitoBean and explicit MediaType imports for JUnit6. No test was deleted or weakened. A previously intermittent NotificationsIT duplicate discard was traced to nearly identical timestamp titles; UUID fixture identities fix the collision without changing production duplicate thresholds or notification assertions.

Browser baseline is Safari16.4+, Chrome111+, Firefox128+ (Tailwind 4), with matching Vite targets and ES2022 types. Actual browser QA here used installed Chrome on Mac; this does not claim execution on Safari/Firefox or old browsers.

Official migration evidence: [Spring Boot 4 guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide), [HTTP mapper property](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/index.html), [Vite support](https://vite.dev/releases), [Tailwind upgrade/browser baseline](https://tailwindcss.com/docs/upgrade-guide), [Node LTS schedule](https://nodejs.org/en/about/previous-releases). Versions and package peers were additionally checked against official registry/Maven metadata, not assumed from candidate research.

## Verification

- Clean npm installation under official Node 24.22.1, whose download checksum was verified. No system Node, authentication or network setting was changed.
- Frontend:119 tests in 21 files, typecheck and production build passed. Existing large-chunk warning remains.
- Backend:350 tests, 0 failures, 0 errors, 3 skipped explicit live tests (LivePrdIT, LiveSplitIT, LiveComposableIT). Includes AuthIT, WorkspaceIsolationIT, WorkspaceMembersIT, WorkspaceConfigIT, WorkspaceRuntimeIT, PostgresRlsIT and two new real HTTP JSON/SSE compatibility tests.
- PostgreSQL18.3 in a fresh disposable database, using NOSUPERUSER/NOBYPASSRLS role; Flyway and raw-SQL RLS tests passed. Original databases were untouched.
- Existing synthetic H2 data from this repository's Boot3 root snapshot loaded under Boot 4 with task retained and no orphan tenant entities. This does not establish compatibility with the original7x24 database/checksums.
- Visual/interaction review:before/after1280px desktop and390px mobile governance views, task table/detail, Radix status menu open/close, tab switching and light theme inspected; layout/design preserved. After Lucide replacement and the scroll-bar2.3.7 pin, task/detail/tabs/theme/mobile checks were repeated; actual SVG stroke1.8 and decorative accessibility attributes were checked. Preview-proxy health, static resources, JSON endpoints and SSE hello passed with only loopback traffic. The optional auth/me request returns401 in the deliberately unauthenticated local QA instance; it is not an application crash.
- Both final Dockerfiles (including Lucide1.61.0 and scroll-bar2.3.7) built and fresh empty-volume containers passed health/auth/empty-OAuth smoke checks; unified image served static frontend and actual wget health check; UID10001 verified. Images are local only. Normal defaults remain auth required, offline and dry-run.
- Gitleaks8.30.1 source scan passed with the unchanged three exact synthetic exceptions. An ignored old task-manifest commit hash triggered a local false positive; the process record was moved outside the snapshot, with no scanner exemption added. The complete staged source tree was also exported and scanned separately from ignored build artifacts; committed-history scan is performed after committing.
- npm audit against official registry:0 vulnerabilities. OSV2.7.0:0 affected packages on complete npm lock plus114 Maven/application/test/packaging-injected components.61 actual nested runtime JARs were reconciled against the inventory. The Boot-injected jarmode helper is explicitly included rather than omitted from dependency:tree. See [remediation detail](security-findings.md) and [SBOM](dependency-sbom.cdx.json).
- Remote CI results are linked in the final delivery after push; failures remain blocking and will be followed to terminal state. Security checksums/tool/download failures still fail the job. No new vulnerability waiver or secret exception was added.

## License and materials

Project Apache-2.0 LICENSE, original root NOTICE statements (with Lucide attribution appended) and all21 vendored open-code-review files remain intact, with upstream copyright/modification statements.136 bundled frontend packages/runtime helpers and16 font assets are inventoried; original notices are emitted in web dist.114 Java/test/packaging components and 61 nested JARs have source-POM/notice records. Project LICENSE/NOTICE are also included in server META-INF. H2's actual MPL2.0/EPL1.0 POM declarations are retained, not replaced by Apache.

[Materials review](materials-audit.md) records removal of all 31 previously unprovenanced icon paths and replacement with official lucide-react1.61.0 static named imports. Exact tarball/lock integrity and original ISC/Feather MIT notices are retained. No icon provenance item remains pending. The react-remove-scroll-bar2.3.8 gap was resolved by a compatible2.3.7 pin with matching registry gitHead/tag and an upstream comparison adding LICENSE only, not by treating a neighboring release as authorized. Inventories are evidence, not legal warranties. No old private materials were restored.

## Remaining limits and approval

Public release remains pending separate explicit owner approval to change visibility/publish. The previously identified icon evidence gap was resolved by the authorized Lucide replacement. Runtime data retention/erasure, privileged background/system database context, real OAuth/GitHub/LLM/SMTP writes, production deployment/network controls, Prometheus/rollback and base-image/OS/JDK vulnerability analysis are not production-security guarantees from these tests. No candidate credential was tested. No claim of zero leakage, universal browser compatibility or production safety is made.
