# Contributing

Use a branch and a reviewable change. Preserve existing UI/stack conventions, attribution, vendor licenses and modification notices. Contributions to project-owned code are offered under Apache-2.0; do not submit material you lack permission to license.

Run `npm ci && npm run verify` and `mvn -f apps/server-java/pom.xml verify`. For auth/tenant changes rerun AuthIT, WorkspaceIsolationIT, WorkspaceMembersIT, WorkspaceConfigIT, WorkspaceRuntimeIT and PostgresRlsIT against a disposable non-superuser PostgreSQL database. Preserve GitHubFake/FakeTools/FakeConfig/Harness and mock HTTP tests. New fixtures must be synthetic.

Do not run live scripts, real GitHub writes, candidate credential checks, model calls, deployment or publish flows during ordinary validation. See [live test boundaries](docs/live-e2e.md). Never commit local `.env`, private keys, runtime databases, unreviewed screenshots, logs or old process records. Keep `.env.example` free of real credentials.

CI secret/dependency scans fail closed. Scope documented synthetic-value exceptions to exact values and fixture paths. Security issues use the private reporting process in [SECURITY](SECURITY.md). Public visibility, releases, images and packages require the owner's separate approval.
