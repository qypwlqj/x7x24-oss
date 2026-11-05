# Workspace and tenant boundaries

This document describes the independent snapshot's current boundaries; private rollout/account history is excluded.

- Every login gets a personal workspace unless the deployer explicitly assigns the reserved legacy workspace via `X7_LEGACY_WORKSPACE_OWNER`.
- `Tenant.LEGACY = ws_legacy` matches V9 backfill and V11 runtime backfill/defaults. V9 creates an unclaimed workspace, no user or membership. This is designed for a fresh database only.
- Workspace roles are owner, admin, member and viewer. Workspace role checks remain separate from `X7_PLATFORM_ADMINS`. Empty platform-admin configuration grants no platform admins.
- Request tenant context fails closed when missing. Membership is checked before a requested workspace header is accepted. Background tasks intentionally use system context and must scope work to the owning workspace.
- Workspace-specific governance, templates, models and integrations use scoped storage keys. The unclaimed legacy seed retains the platform/global storage compatibility path. Only a trusted platform operator should claim it.
- PostgreSQL uses `FORCE ROW LEVEL SECURITY` with the `x7.ws` session variable set when borrowing a connection. Normal workspace sessions see their own rows. System/platform contexts intentionally see multiple workspaces. Database roles must not have SUPERUSER or BYPASSRLS.
- Audit chains are platform-wide for integrity; API list/read operations remain tenant filtered. Newly written audit/evidence payloads are scrubbed before hashing. Existing records are not rewritten.

Validation: `AuthIT`, `WorkspaceIsolationIT`, `WorkspaceMembersIT`, `WorkspaceConfigIT`, `WorkspaceRuntimeIT`, `PostgresRlsIT`. The PostgreSQL tests require a disposable database and ordinary application role. See [installation](../installation.md) and [minimum permissions](../permissions.md).

Limitations: this snapshot edits historical migration SQL to remove inherited identities. It is incompatible with an old database's Flyway checksums and identity layout. Do not use `repair` to force it onto an old database. Legacy database conversion, replacement of the system-context privilege model and a complete retention/erasure workflow require separate reviewed work.
