# Security policy

This is experimental software; no production-security or zero-leakage guarantee is made. Only this snapshot is under review and no fixed response SLA is promised.

Report a suspected vulnerability privately using GitHub's private vulnerability reporting if enabled by the repository owner. If that channel is unavailable, contact the repository owner privately to arrange a secure channel before sharing exploit details. Do not post credentials, private code, live account identifiers or exploit payloads in public issues. Provide affected revision, minimal offline/mock reproduction, expected boundary and impact. Never test candidate credentials against real services.

Default operation requires authentication, uses offline adapters and dry-run, has no OAuth Client ID, target repository, legacy claimant or platform admin. Real writes, model traffic and deployment need explicit operator setup. Read [data policy](docs/data-policy.md), [permissions](docs/permissions.md) and [installation](docs/installation.md).

Secret scans and dependency scans must block CI on findings **or tool/network failure**. Synthetic exceptions must be exact values scoped to the specific fixture paths; never allowlist the whole test tree. New accepted findings require review. A clean snapshot scan does not audit the old repository's entire history and does not prove absence of secrets.

Report vulnerabilities via GitHub private vulnerability reporting when enabled. Do not publish credentials, private infrastructure details, or exploit payloads in public issues. Do not test candidate credentials against real services.
