# Install a fresh instance

Experimental snapshot. Requires Node.js24.22.1 LTS (24.x), JDK 21 and Maven 3.9+. Use a new data directory or empty PostgreSQL database. Migrations were neutralized for this independent snapshot; **do not point it at an old 7x24 database or run Flyway repair to bypass checksum differences**. Old-database conversion requires a separate reviewed plan and backup.

```sh
npm ci
npm run verify
cd apps/server-java
mvn verify
cd ../..
```

For a local, credential-free H2 demonstration only:

```sh
DATA_DIR=/tmp/x7x24-demo ADAPTER_MODE=offline ADAPTER_DRY_RUN=true \
ENGINE_ENABLED=false NOTIFICATION_WORKER_ENABLED=false \
java -jar apps/server-java/target/x7x24-server.jar \
  --server.address=127.0.0.1 --x7x24.auth.required=false \
  --x7x24.web-dir=./no-web
```

In a second terminal run `PORT=5173 HOSTNAME=127.0.0.1 npm run start -w @x7x24/web`, then open `http://localhost:5173`. The production build includes a standalone Next.js server; Java serves API requests on port 8787 through the frontend proxy. For development, use `npm run dev:web` instead.

This explicit loopback demo disables auth for fake data. Normal startup keeps authentication required. Before enabling logins, create your own GitHub App, set `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET` (web login), `X7_PUBLIC_URL`, an exact `X7_ALLOWED_ORIGINS`, `X7_ALLOWED_GITHUB_LOGINS`, and an explicit `X7_PLATFORM_ADMINS` list. Keep `X7_AUTH_REQUIRED=true`, `ADAPTER_MODE=offline`, `ADAPTER_DRY_RUN=true` until you intentionally configure an isolated target. There is no default OAuth App, target repository or platform admin.

Login authorization is reused for repository selection and refreshed automatically. Set the App Setup URL to `<backend public URL>/api/auth/github/setup` to return from repository installation and organization approval. For unattended delivery, configure `GITHUB_APP_PRIVATE_KEY` and the Client ID (or `GITHUB_APP_ID`); explicitly bound repositories then use renewable installation tokens scoped to the selected repository. See [integrations](integrations.md).

Each authenticated user receives a new personal workspace. The reserved `ws_legacy` seed workspace is unclaimed. Only a deployer-supplied `X7_LEGACY_WORKSPACE_OWNER` can claim it; leave that variable unset for normal new installations. The legacy seed shares platform config for compatibility with internal fixtures; only a trusted platform operator should ever claim it.

`.env.example` documents available values. The Java process does not automatically load a root `.env`; export only the values you actually configure. Do not export blank numeric example fields. Use a protected secret store for real credentials. Persist and restrict the data directory and `X7_SECRET_KEY`; integration credentials are encrypted at rest with that key. Do not commit the generated key, database or logs.

PostgreSQL: set `DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/x7x24`, `DATABASE_USER` and `DATABASE_PASSWORD` for a dedicated application role that is neither superuser nor `BYPASSRLS`. Flyway needs schema DDL rights; RLS uses `FORCE ROW LEVEL SECURITY`. The optional Compose `postgres` service is a bootstrap example, not a safe application-role configuration; create a non-superuser role and empty database before connecting the app. Run `PostgresRlsIT` using `X7_PG_TEST_URL`, `X7_PG_TEST_USER`, `X7_PG_TEST_PASSWORD` on a disposable test database.

Docker (local build only): `docker build -t x7x24:local .`. Run with a disposable volume and `-p 127.0.0.1:8787:8787`; keep auth enabled unless deliberately using the loopback fake-data demo above. The optional `render.yaml`, `vercel.json` and manually triggered Next.js build workflow are templates only; preparing this snapshot does not deploy anything. Next.js App Router uses a Node.js runtime and cannot use the former GitHub Pages static deployment. See [deployment](deploy.md).

Browser baseline after Tailwind4: Safari16.4+, Chrome111+, Firefox128+. TypeScript targets ES2022. Node24.22.1 is pinned in .nvmrc, engines, CI and Docker; this task uses an isolated official Node binary and does not replace system Node.

Boot 4 uses the documented deprecated Jackson 2 bridge for current canonical JSON compatibility; Spring 7, Tomcat 11 / Servlet 6.1 and the Flyway starter are covered by the test suite. Pin exact versions via the Maven BOM and `package-lock.json`.
