export type Plan = { schema: 3; revisionId: string; taskId: string; workspaceId: string; projectId: string; revisionNo: number; parentRevisionId: string | null; baseSha: string; policyDigest: string; budgetUsd: number; nodes: Node[]; controls: { from: string; to: string }[]; facts: Record<string, boolean> };
export type Node = { id: string; logicalKey: string; definitionRef: string; config: Record<string, unknown>; bindings: Binding[]; roles: Record<string, unknown> };
export type Binding = { id: string; port: string; ordinal: number; versionId: string | null; producerNode: string | null; outputPort: string | null };
export type Compiled = { plan: Plan; order: string[]; hash: string; diagnostics: { node: string; port: string; code: string; message: string }[] };
export type Candidate = { id: string; parentId: string | null; taskVersion: number; compiled: Compiled; provenance: string };
export type Artifact = { id: string; artifact_type: string; content_digest: string; approval_subject: string; body: string };
export type SavedPlan = { id: string; plan_hash: string; status: string; body: string };
export type Approval = { id: string; subject_digest: string; decision: string; expires_at: string; revoked_at: string | null };
export type EditorState = { taskVersion: number; activeRevisionId: string | null; artifacts: Artifact[]; plans: SavedPlan[]; candidates: { id: string; body: string }[]; approvals: Approval[] };
export const inputTypes: Record<string, string> = { requirements: 'RequirementSpec', acceptance: 'AcceptanceCriteria', repository: 'RepoSnapshot', scopeEvidence: 'RepositoryProfile', design: 'DesignSpec', apiContract: 'APIContract', failureEvidence: 'TestReport' };
export function canSaveCandidate(c: Candidate | undefined, version: number, dirty: boolean) { return !!c && !dirty && c.taskVersion === version && c.compiled.diagnostics.length === 0; }
export function approved(approvals: Approval[], digest: string, now = Date.now()) { return approvals.some(a => a.subject_digest === digest && a.decision === 'APPROVED' && !a.revoked_at && Date.parse(a.expires_at) > now); }
export function parseDraft(text: string): Plan { const p = JSON.parse(text); if (p.schema !== 3 || !Array.isArray(p.nodes)) throw Error('草稿必须是 schema=3 的节点计划'); return p; }
export type Definition = { ref: string; executor: string; available: boolean; inputs: Port[]; outputs: Port[] };
export type Port = { name: string; type: string; requirement: string; condition: string | null; approvalRequired: boolean; maxItems: number };
export function renameNode(plan: Plan, oldId: string, id: string): Plan {
 return { ...plan, nodes: plan.nodes.map(n => ({ ...n, id: n.id === oldId ? id : n.id, bindings: n.bindings.map(b => ({ ...b, producerNode: b.producerNode === oldId ? id : b.producerNode })) })), controls: plan.controls.map(e => ({ from: e.from === oldId ? id : e.from, to: e.to === oldId ? id : e.to })) };
}
export function bindPort(node: Node, port: string, ordinal: number, source: string): Node {
 const bindings = node.bindings.filter(b => b.port !== port || b.ordinal !== ordinal);
 if (source) { const value = JSON.parse(source); bindings.push({ id: `${port}-${ordinal}`, port, ordinal, versionId: value.versionId ?? null, producerNode: value.producerNode ?? null, outputPort: value.outputPort ?? null }); }
 return { ...node, bindings: bindings.sort((a, b) => a.port.localeCompare(b.port) || a.ordinal - b.ordinal) };
}
