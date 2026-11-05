# Deployment templates

The frontend runs Next.js 16 App Router, React 19 and Tailwind CSS 4. It uses history URLs and requires a Node.js service for dynamic pages; GitHub Pages static hosting has been removed. The manual `web-build.yml` workflow verifies and packages a standalone server artifact. It does not publish a site.

## Single container

Build from the repository root: `docker build -t x7x24:local .`. The root Dockerfile starts Next.js on public `PORT` (8787 by default) and Java on loopback port 8788. Next.js forwards `/api/*`, including SSE and GitHub callbacks, to Java. SSE responses set `no-transform` and `X-Accel-Buffering: no` so compression and proxy buffering do not delay events. Persist `/data` and configure authentication using the public frontend origin. Health checks go through the frontend proxy.

## SSE keepalive and recovery

Task streams (`/api/tasks/{id}/stream`) and the global stream (`/api/events`) send named `heartbeat` events every 15 seconds from a dedicated, lifecycle-managed scheduler. They do not share the business scheduler with coding or review work. The `hello` frame advertises `heartbeatMs` and a temporary `connectionId`; heartbeat frames have no business event ID and are not written into task history. Task connections rotate after 30 minutes and resume from the last received sequence.

The task UI detects 45 seconds without a heartbeat or business event when the server advertises heartbeat support. Older servers with comment-only heartbeats retain transport-error recovery without this watchdog. Connection setup and progress requests have a 10-second timeout. Initial failures retry after 1, 2 and 4 seconds; subsequent failures use three-second polling while probing SSE after approximately 15, 30 and then 60 seconds, with jitter. A lost established connection starts polling immediately. Every attempt obtains a new one-time ticket. A successful handshake cancels polling, and obsolete callbacks cannot overwrite the new connection. Going online or returning to the foreground triggers recovery; background tabs have a short catch-up grace period. HTTP 401, 403 and 404 stop retries and display an actionable status.

Outer proxies must forward SSE incrementally, avoid compression/buffering, and allow idle periods longer than the heartbeat interval. Check the status popover for the last successful sync, last heartbeat, retry count, next attempt and recent error. Correlate its connection ID with `sse_open`, `sse_close` and `sse_rejected` server logs. Reasons distinguish timeout, send failure, transport error, capacity limits and server shutdown; a browser `EventSource` error alone cannot reveal an HTTP status or identify which proxy failed. Neither frontend diagnostics nor these server logs retain ticket URLs or authorization headers.

The real Next.js rewrite smoke check uses isolated test data, blocks the business scheduler and keeps a quiet connection open for 90 seconds. Run it explicitly from `apps/server-java` with `mvn -q -Dtest=TaskStreamIT#namedHeartbeatsSurviveNextProxy -Dsse.proxy-smoke=true test`. It starts a temporary local Next.js development server; ordinary test runs skip this longer check.

## Separate frontend and API

`apps/server-java/Dockerfile` runs only Java. Build the frontend from the repository root with `docker build -f apps/web/Dockerfile --build-arg API_PROXY_TARGET=https://your-api.example -t x7x24-web:local .`; it listens on 5173. The API target must be reachable from the Next.js service. The rewrite target is embedded at build time: rebuild after changing it.

For a local production build, run `npm ci && npm run verify`, then `PORT=5173 npm run start -w @x7x24/web`. The start script copies `.next/static` and `public` into `.next/standalone/apps/web` before launching the generated server. Deploying the standalone directory directly requires copying those two asset folders, as shown in the Dockerfiles and workflow.

For Vercel, select `apps/web` as the Root Directory and the Next.js preset, use the workspace install/build commands, and keep the default `.next` output. The root `vercel.json` also provides commands and output paths for a repository-root build. Configure `API_PROXY_TARGET` with an externally reachable API URL before building. No project, domain or secret is linked by this change.

`NEXT_PUBLIC_API_BASE` can optionally bypass the proxy for a separately hosted API. Leave it empty for same-origin requests. `NEXT_PUBLIC_BASE_PATH` optionally sets a deployment subpath, for example `/7x24`; it must be set at build time. Never put credentials in `NEXT_PUBLIC_*` variables. Use the frontend URL for `X7_ALLOWED_ORIGINS` and `NOTIFICATION_APP_URL`; GitHub's OAuth and Setup callbacks must reach the API routes through the public proxy or the separate API host. Session tokens return in a temporary URL fragment which is removed after login, while the requested page is preserved.

No deployment is performed by preparing these templates. Authentication, dry-run defaults, data persistence and other installation requirements remain described in [installation](installation.md), [permissions](permissions.md) and [data policy](data-policy.md). `render.yaml` remains an API-only template with deployer-supplied origins and credentials.
