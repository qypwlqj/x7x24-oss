# Dependency security remediation

Status: npm audit **0 vulnerabilities** and OSV **0 affected packages** on the final locked graph. Gitleaks uses the unchanged three exact synthetic-fixture exceptions. This is not public-release approval. See [materials review](materials-audit.json) for the final source/notice evidence.

| Package | Previous | Current/result | Historical advisories |
|---|---|---|---|
| org.springframework:spring-webmvc | 6.2.19 | 7.0.9 | [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8), [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) |
| @vitest/mocker | 2.1.9 | 4.1.12 | [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9), [GHSA-qxg6-89mg-rqv8](https://github.com/advisories/GHSA-qxg6-89mg-rqv8) |
| braces | 3.0.3 | removed | [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) |
| esbuild | 0.21.5 | 0.25.12 | [GHSA-67mh-4wv8-2f99](https://github.com/advisories/GHSA-67mh-4wv8-2f99) |
| postcss-selector-parser | 6.1.5 | removed | [GHSA-rj75-hqrm-r3gf](https://github.com/advisories/GHSA-rj75-hqrm-r3gf) |
| tinypool | 1.1.1 | removed | [GHSA-5gmw-xhrv-c9v3](https://github.com/advisories/GHSA-5gmw-xhrv-c9v3), [GHSA-85c8-ppgw-ccpr](https://github.com/advisories/GHSA-85c8-ppgw-ccpr) |
| vite | 5.4.21 | 6.4.4 | [GHSA-4w7w-66w2-5vf9](https://github.com/advisories/GHSA-4w7w-66w2-5vf9), [GHSA-587h-7wmj-h5pm](https://github.com/advisories/GHSA-587h-7wmj-h5pm), [GHSA-698v-4r3x-3vcv](https://github.com/advisories/GHSA-698v-4r3x-3vcv), [GHSA-9jrq-w75r-8gcw](https://github.com/advisories/GHSA-9jrq-w75r-8gcw), [GHSA-fx2h-pf6j-xcff](https://github.com/advisories/GHSA-fx2h-pf6j-xcff), [GHSA-r825-w8mm-5pxw](https://github.com/advisories/GHSA-r825-w8mm-5pxw), [GHSA-rq7h-c2jc-7f22](https://github.com/advisories/GHSA-rq7h-c2jc-7f22), [GHSA-v6wh-96g9-6wx3](https://github.com/advisories/GHSA-v6wh-96g9-6wx3) |
| vitest | 2.1.9 | 4.1.12 | [GHSA-5xrq-8626-4rwp](https://github.com/advisories/GHSA-5xrq-8626-4rwp), [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9) |

Additional Boot 4 BOM fixes: Jackson 3 **3.1.5→3.1.8** removes the seven core/databind matches listed in [the JSON report](security-findings.json); the separately documented Jackson 2 bridge uses **2.21.8**. Boot 4 Tomcat **11.0.24→11.0.27** avoids carrying known11.x advisories. Java remains21. No forced transitive-major override, new secret exemption or vulnerability-ignore entry was added.

Coverage: complete npm lock (including platform optional/dev entries), 114 Maven/application-test/packaging-helper components, and comparison to61 actual nested server libraries. SBOM generation detects the Boot-injected jarmode helper outside dependency:tree and fails on unknown uninventoried packaged jars. CI builds before scanning and blocks on findings or tool failure. Source and Git history are scanned separately. Base OS/JDK/native-tool vulnerabilities and unknown vulnerabilities are outside this application-package scan.

The existing runtime/development classifications and impact observations are retained per advisory in the JSON report; fixes were applied regardless of conditional reachability.

## Spring severity discrepancy

Spring classifies [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) as LOW (SSE with view fragments and attacker-controlled streamed content) and [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) as MEDIUM (XsltView with particular rendering/mapping conditions). The GitHub/OSV data used by the original CI assigned CVSS9.8 to both. This discrepancy is recorded, not used as a waiver: Spring Framework7.0.9 is the official OSS fix for both, and the final scanner no longer reports them.

## Remaining public-release decisions

- All 31 previously unprovenanced icon paths were replaced with official lucide-react1.61.0; package/tarball/lock integrity and original ISC/MIT notices are retained. No unresolved icon-source item remains.
- react-remove-scroll-bar is pinned to compatible2.3.7 with exact source/tag/tarball evidence;2.3.8 was not cleared by inference. See materials-audit.md and SOURCE.json.
- Jackson 2 compatibility support is deprecated by Boot 4; a later dedicated Jackson 3 migration must preserve canonical evidence/audit bytes.
- Public visibility and publishing require separate owner approval even when CI is green.
