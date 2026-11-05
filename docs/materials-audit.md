# Third-party and visual-material review

The project license remains Apache-2.0; it does not replace dependency or material terms. Original root NOTICE statements (with an added Lucide attribution) and all vendored open-code-review notices/rules/modification statements remain unchanged. The application JAR now retains project LICENSE/NOTICE in META-INF; original nested library notices are retained inside their JARs.

- [Bundled frontend inventory](bundled-frontend-licenses.json) records the previous Vite build. The Next.js build now emits a conservative installed production dependency graph, including server packages and compiled vendor notices, to `apps/web/public/third-party-licenses.json` and `THIRD_PARTY_NOTICES.txt`. These are also included in the standalone deployment; they do not claim exact per-chunk attribution. Missing package-provided notices remain visible as empty notice lists. The audit script reads the new outputs and matches fonts in `.next/static/media`. One exact-source supplement is documented below.
- [Maven inventory](maven-dependency-licenses.json):114 resolved/test/packaging components and 61 actual nested runtime JARs, POM license declarations (including inheritance), source POM URLs and hashes of existing JAR notices. H2 declares MPL2.0 and EPL1.0; it is not relicensed Apache. This inventory is metadata/notice review, not a legal opinion on every distribution obligation.
- [Material inventory](materials-audit.json):16 actual emitted font assets matched byte-for-byte to package sources. Rajdhani, Orbitron and JetBrains Mono retain their exact package-provided OFL notices and copyright statements. System fallback fonts are referenced, not redistributed.
- No tracked raster images, SVG asset files, video or font binaries were restored. Existing charts and CSS noise are source-generated geometry/effects; UI icons come from the source-verified Lucide package. Runtime avatar/user content is not part of the distributed asset inventory.

## Resolved by a compatible source-verified patch: react-remove-scroll-bar

The previous2.3.8 npm artifact declared MIT but omitted notice; its published gitHead could not be resolved in the declared upstream, and no matching tag was found. This gap was not waived or covered by assuming an adjacent version's license. Historical evidence is retained in [license-provenance-history.json](license-provenance-history.json).

The existing react-remove-scroll2.7.2 requirement is `^2.3.7`. We therefore pin the compatible2.3.7 patch. Its official registry gitHead exactly matches tag v2.3.7 at `29e9fcd1eecf7d3b77a767941c4a57fe461fc1e4`. The official tarball's SHA512 is checked against package-lock.json. The upstream comparison from that tag to `8ca9ba5ea52de03308fe8ced94f7b159a44d28ff` changes **only LICENSE**, with no source changes; that commit's package.json still says2.3.7. The exact source code and its added MIT notice are therefore traceable, without inferring permission for 2.3.8.

Original MIT text, fixed source comparison, registry/tag metadata and content integrity are retained in [SOURCE.json](../apps/web/third_party/react-remove-scroll-bar/SOURCE.json), included in the emitted frontend inventory. The same-patch pin satisfies declared peer/dependency ranges and is not a vulnerability waiver. Functional tests, browser checks and security scans are rerun. This evidence is not a general legal warranty.

## UI icons replaced with verified Lucide artifacts

All 31 previous inline paths were removed from [Icon.tsx](../apps/web/src/ui/Icon.tsx) under explicit owner authorization. The wrapper now uses static named imports from official `lucide-react@1.61.0`; existing semantic names, default16px size,1.8 stroke, currentColor, decorative aria-hidden/focusable defaults and caller overrides are preserved. Button accessible labels are unchanged. Custom chart geometry and text branding were preserved.

The exact official npm tarball is SHA512-matched to package-lock.json, and its LICENSE is byte-matched to the installed package. It includes ISC and the complete Feather-derived MIT notice; both are retained in [the source evidence](../apps/web/third_party/lucide-react/SOURCE.json), the original LICENSE file, aggregate frontend notices and built artifacts. The metadata has no gitHead, so no tag/commit mapping is asserted. Official registry content, integrity and notices provide the package evidence. The icon mapping is in materials-audit.json. No unresolved icon-source item remains.

All inventories avoid legal guarantees. CI passing does not approve public visibility, publication or deployment.
