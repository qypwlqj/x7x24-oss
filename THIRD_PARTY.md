# Third-party materials

## alibaba/open-code-review

| | |
|---|---|
| Upstream | https://github.com/alibaba/open-code-review |
| Version | `v1.13.5` (tag commit `abb7abcd`, released 2026-11-03) |
| License | Apache License 2.0 (`apps/agent-runner/ocr/LICENSE`) |
| Copyright | Copyright 2026 Alibaba |
| Used for | 「逐行审查」node (`CODE_REVIEW`, skill `ocr-review@1`) |

Vendored file: `apps/agent-runner/ocr/skills/open-code-review/SKILL.md`, a verbatim, unmodified copy of
upstream `skills/open-code-review/SKILL.md` (sha256 `be45ee1303e5106cd36f20bd7854ea1a5bb41b0d6ae045953cb643201bcd715e`).

The `ocr` binary itself is not committed. `apps/agent-runner/install-ocr.sh` downloads the official
`opencodereview-linux-amd64` release asset of v1.13.5 on the runner host and refuses to install it unless its
sha256 is `f9d918dd8b461719be975430e0f7396b4e642b465ffa490d651ace0d8c989171`. runnerd runs it unmodified inside
the sandbox container as the skill describes (`ocr review --audience agent --format json`); ocr calls the model
itself, does its own file selection, grouping and verification, and x7x24 only parses its JSON output.

## Font packages in the locked frontend build

The following notices are copied verbatim from the installed package LICENSE files. Copyright attribution is the package-provided text; no new ownership claim is made.

| Package | Installed version | Declared license | Retained notice |
|---|---|---|---|
| @fontsource/ibm-plex-mono | 5.3.0 | OFL-1.1 (package LICENSE) | `third_party/fonts/ibm-plex-mono-OFL.txt` |
| @fontsource/jetbrains-mono | 5.3.0 | OFL-1.1 (package LICENSE) | `third_party/fonts/jetbrains-mono-OFL.txt` |
| @fontsource/orbitron | 5.3.0 | OFL-1.1 (package LICENSE) | `third_party/fonts/orbitron-OFL.txt` |
| @fontsource/rajdhani | 5.3.0 | OFL-1.1 (package LICENSE) | `third_party/fonts/rajdhani-OFL.txt` |

## Dependency inventory and limits

See `docs/dependency-licenses.json` for package-declared licenses of installed npm dependencies. These declarations are evidence of package metadata, not a legal review. See `docs/maven-dependency-licenses.json` for resolved Maven POM declarations, including explicitly marked unverified metadata. Runtime Maven dependencies use their respective upstream licenses; Spring Boot dependency-management does not relicense dependencies. The complete resolved Maven metadata, packaged notices, frontend modules and actual font bytes are inventoried in the reports below. This evidence does not constitute a legal compatibility opinion. Unknown/missing metadata is marked explicitly. Project Apache-2.0 does not replace third-party terms.

## Upgrade audit and packaged evidence

See docs/bundled-frontend-licenses.json, docs/dependency-licenses.json and docs/maven-dependency-licenses.json. The web build emits original notices and its inventory, including the font families listed above. The server preserves original nested-library notices and project LICENSE/NOTICE. UI icons use official Lucide named imports. H2 retains its own MPL 2.0 / EPL 1.0 terms. No dependency is relicensed by the project Apache-2.0 declaration.


## Lucide UI icons

All 31 semantic UI icons use `lucide-react@1.61.0`, from the official https://github.com/lucide-icons/lucide repository and npm registry package. React 19 satisfies its published peer range. Imports are centralized in `apps/web/src/ui/Icon.tsx`, with no old copied paths retained. Only named imported icons are bundled; chart geometry, text branding and runtime avatars were not replaced.

The exact published package LICENSE contains the ISC notice (Copyright (c) 2026 Lucide Icons and Contributors) and MIT notice for Feather-derived icons (Copyright (c) 2013-present Cole Bemis). Both original texts are retained at `apps/web/third_party/lucide-react/LICENSE`, in the frontend aggregate and build output. [SOURCE.json](apps/web/third_party/lucide-react/SOURCE.json) records official registry/tarball URLs and SHA512/SHA256 checks against the lockfile; no unresolved git tag is asserted as proof. See [official license](https://lucide.dev/license) and [React documentation](https://lucide.dev/guide/react).
