# Live test boundaries

The default validation uses `GitHubFake`, `FakeTools`, `FakeConfig`, `Harness`, local mock HTTP servers and `application-test.yml`. These fixtures remain in the snapshot. Historical private sandbox results, issue/PR/release links, screenshots and videos are excluded; they are not evidence for this snapshot.

`npm run verify:all` does not invoke live scripts. Both shell and direct Python drivers require explicit `LIVE=1`; shell drivers additionally require `TARGET_REPO=owner/disposable-repo`. They can close issues, delete/reset example files, create commits and PRs, merge and create releases. Never point them at this repository or an existing project without separate authorization. They are not run during snapshot preparation. The legacy screenshot integration script also mutates credentials/device flow/repository binding and requires its own explicit gate; do not use it to claim safe demo screenshots.

Preserve these test boundaries in future isolated mock scenarios:

| Scenario | Expected boundary |
|---|---|
| Successful delivery | Issue intake, review, CI and approval evidence remain traceable |
| CI failure | Repair is bounded and cannot skip acceptance gates |
| Forbidden paths | No write to protected files or workflows |
| Kill switch | Stops further external writes |
| Prompt injection | Repository content remains untrusted data |
| Ambiguous requirements | Clarify or discard rather than invent scope |
| Tenant access | Cross-workspace IDs are rejected; viewer stays read-only |
| Audit | Evidence hashes and append-only chain verify after redaction |

Any future live run must use a disposable target, its own minimal credentials and explicit approval for publishing/merging there. Results belong in ignored local output and require review before sharing.
