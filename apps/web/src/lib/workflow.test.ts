import { expect, it } from 'vitest';
import { approved, canSaveCandidate, parseDraft, type Candidate } from './workflow';
it('stale, dirty, and invalid candidates cannot be saved', () => {
 const c = { taskVersion: 3, compiled: { diagnostics: [] } } as unknown as Candidate;
 expect(canSaveCandidate(c, 3, false)).toBe(true); expect(canSaveCandidate(c, 4, false)).toBe(false); expect(canSaveCandidate(c, 3, true)).toBe(false);
 expect(canSaveCandidate({ ...c, compiled: { ...c.compiled, diagnostics: [{ node: 'dev', port: 'design', code: 'REQUIRED_INPUT', message: '缺少设计' }] } }, 3, false)).toBe(false);
});
it('revoked, expired, and wrong digest approvals never enable start', () => {
 const a = { id: 'a', subject_digest: 'fixed', decision: 'APPROVED', revoked_at: null, expires_at: '2026-01-02T00:00:00Z' };
 const now = Date.parse('2026-01-01T00:00:00Z'); expect(approved([a], 'fixed', now)).toBe(true); expect(approved([a], 'different', now)).toBe(false);
 expect(approved([{ ...a, revoked_at: '2026-01-01T00:00:00Z' }], 'fixed', now)).toBe(false); expect(approved([a], 'fixed', now + 86400001)).toBe(false);
});
it('invalid JSON or old workflow schemas remain editor errors', () => { expect(() => parseDraft('{')).toThrow(); expect(() => parseDraft('{"schema":2,"nodes":[]}')).toThrow(); });
import { bindPort, renameNode, type Plan, type Node } from './workflow';
it('structured rename preserves explicit data and control dependencies', () => {
 const input = { id: 'requirement', port: 'requirements', ordinal: 0, versionId: null, producerNode: 'source', outputPort: 'requirements' };
 const plan = { nodes: [{ id: 'source', bindings: [] }, { id: 'dev', bindings: [input] }], controls: [{ from: 'source', to: 'dev' }] } as unknown as Plan;
 const result = renameNode(plan, 'source', 'requirements-2'); expect(result.nodes[1].bindings[0].producerNode).toBe('requirements-2'); expect(result.controls[0].from).toBe('requirements-2'); expect(plan.nodes[0].id).toBe('source');
});
it('structured port changes keep fixed versions and list ordinals distinct', () => {
 let node = { bindings: [] } as unknown as Node;
 node = bindPort(node, 'failureEvidence', 0, JSON.stringify({ versionId: 'v1' })); node = bindPort(node, 'failureEvidence', 1, JSON.stringify({ versionId: 'v2' }));
 expect(node.bindings.map(b => b.versionId)).toEqual(['v1', 'v2']); node = bindPort(node, 'failureEvidence', 0, ''); expect(node.bindings).toHaveLength(1); expect(node.bindings[0].versionId).toBe('v2');
});
