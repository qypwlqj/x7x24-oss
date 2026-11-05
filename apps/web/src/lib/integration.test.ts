import { describe, expect, it } from 'vitest';
import { GLOSSARY, lookup } from './glossary';
import { MODE_NAME, fmtExpiry, summarizeChecks, tabOf } from './integration';

describe('integration helpers', () => {
  it('uses plain language for modes and never leaks internal names', () => {
    expect(MODE_NAME.observe).toBe('只记录模式'); expect(MODE_NAME.execute).toBe('真实执行模式'); expect(MODE_NAME.experience).toBe('体验模式');
    for (const v of Object.values(MODE_NAME)) expect(v).not.toMatch(/dry-?run|ADAPTER/i);
  });
  it('summarizes checks', () => { expect(summarizeChecks([{ status: 'ok' }, { status: 'ok' }, { status: 'fail' }, { status: 'unknown' }])).toMatchObject({ ok: 2, fail: 1, warn: 0, unknown: 1 }); });
  it('tab routing and expiry text', () => { expect(tabOf('status')).toBe('status'); expect(tabOf(undefined)).toBe('connect'); expect(tabOf('projects')).toBe('connect'); expect(fmtExpiry('2026-10-12T01:02:03Z')).toBe('2026-10-12 01:02 UTC'); expect(fmtExpiry(null)).toContain('长期有效'); });
  it('glossary covers the terms the UI uses and every entry has text', () => {
    for (const k of ['L1', 'L2', 'L3', 'soft', 'hard', 'loop', 'canary', 'killSwitch', 'rateLimit', 'retries', 'backoff', 'adapterMode', 'dryRun']) expect(lookup(k), k).toBeTruthy();
    for (const [k, g] of Object.entries(GLOSSARY)) { expect(g.name, k).toBeTruthy(); expect(g.text.length, k).toBeGreaterThan(8); }
  });
});

import { applySuggestion, defaultProjectConfig, isUndetected } from '../catalog';
describe('stack suggestion', () => {
  it('default is undetected, not Node CommonJS', () => { expect(isUndetected(defaultProjectConfig().technology)).toBe(true); expect(defaultProjectConfig().technology).not.toContain('CommonJS'); });
  it('applies suggestion fields', () => {
    const c = applySuggestion(defaultProjectConfig(), { technology: 'Go', modules: [{ name: 'b', path: 'backend/', language: 'Go', testCommand: 'go test ./...', buildCommand: '' }], allowedPaths: ['backend/'], allowedExtensions: ['go'], pathRules: [{ prefix: '.github/', level: 'forbidden' }], discoveryRoots: ['backend/'], workflow: 'test.yml', evidence: [] });
    expect(c.allowedPaths).toEqual(['backend/']); expect(c.workflow).toBe('test.yml'); expect(c.discovery.roots).toEqual(['backend/']); expect(isUndetected(c.technology)).toBe(false);
  });
});
