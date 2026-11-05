/** Preserve the previous value on failure; a successful refresh clears a stale error. */
export async function refreshResource<T>(fetch: () => Promise<T>, accept: (value: T) => void, error: (message: string) => void) {
  try { accept(await fetch()); error(''); } catch (e) { error(e instanceof Error ? e.message : String(e)); }
}
