import { expect, it } from 'vitest';
import { refreshResource } from './resource';
import { parseRoute } from '../router';
it('keeps stale data visible and clears failure after recovery', async () => {
  let error = ''; let value = 'old';
  await refreshResource(async () => { throw Error('offline'); }, x => { value = x; }, e => { error = e; });
  expect(error).toBe('offline'); expect(value).toBe('old');
  await refreshResource(async () => 'new', x => { value = x; }, e => { error = e; });
  expect(error).toBe(''); expect(value).toBe('new');
});
it('parses task evidence links without mixing query into the task ID', () => {
  expect(parseRoute('/task/task-1?tab=evidence&focus=QA')).toEqual(['task', 'task-1', 'evidence', 'QA']);
  expect(parseRoute('/task/task-1/verdicts')).toEqual(['task', 'task-1', 'verdicts']);
  expect(parseRoute('/task/%E0%A4%A')).toEqual(['task', '%E0%A4%A']);
});
