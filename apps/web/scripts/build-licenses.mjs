import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Next emits a server as well as browser chunks. Inventory the installed production
// dependency graph conservatively, retaining original notices (including Next's vendors).
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packages = new Map();
const visited = new Set();
function packageDirectory(name, from) {
  for (let dir = from; ; dir = dirname(dir)) {
    const candidate = join(dir, 'node_modules', name);
    if (existsSync(join(candidate, 'package.json'))) return candidate;
    if (dir === dirname(dir)) return null;
  }
}
function visit(name, from) {
  if (name.startsWith('@x7x24/')) return;
  const dir = packageDirectory(name, from);
  if (!dir || visited.has(dir)) return;
  visited.add(dir);
  const meta = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'));
  const notices = readdirSync(dir).filter(file => /^(license|licence|copying|notice)([.\-_]|$)/i.test(file))
    .map(file => ({ file, text: readFileSync(join(dir, file), 'utf8') }));
  if (!notices.length && meta.name === 'react-remove-scroll-bar') {
    const evidence = JSON.parse(readFileSync(join(root, 'third_party/react-remove-scroll-bar/SOURCE.json'), 'utf8'));
    if (evidence.version !== meta.version) throw new Error('Review the react-remove-scroll-bar license supplement');
    notices.push({ file: `LICENSE (upstream ${evidence.upstreamCommit})`, text: readFileSync(join(root, 'third_party/react-remove-scroll-bar/LICENSE'), 'utf8') });
  }
  if (name === 'next') {
    const compiled = join(dir, 'dist/compiled');
    for (const file of readdirSync(compiled, { recursive: true })) {
      if (/(^|\/)(license|licence|copying|notice)([.\-_]|$)/i.test(file)) {
        const full = join(compiled, file);
        try { notices.push({ file: 'dist/compiled/' + file, text: readFileSync(full, 'utf8') }); } catch { /* directories */ }
      }
    }
  }
  packages.set(`${meta.name}@${meta.version}`, { name: meta.name, version: meta.version, declaredLicense: meta.license ?? 'UNVERIFIED', repository: meta.repository ?? 'UNVERIFIED', notices });
  for (const dependency of Object.keys(meta.dependencies ?? {})) visit(dependency, dir);
  for (const optional of Object.keys(meta.optionalDependencies ?? {})) visit(optional, dir);
}
const app = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
for (const name of Object.keys(app.dependencies)) visit(name, root);
visit('tailwindcss', root);
const rows = [...packages.values()].sort((a, b) => a.name.localeCompare(b.name));
if (!rows.some(row => row.name === 'next') || !rows.some(row => row.name === 'react') || !rows.some(row => row.name.startsWith('@fontsource/'))) throw new Error('Incomplete frontend license inventory');
const out = join(root, 'public'); mkdirSync(out, { recursive: true });
writeFileSync(join(out, 'third-party-licenses.json'), JSON.stringify({ scope: 'Installed production dependency graph for the Next.js server and browser; conservative inventory, not an exact per-chunk attribution or legal assurance', packages: rows }, null, 2) + '\n');
writeFileSync(join(out, 'THIRD_PARTY_NOTICES.txt'), rows.map(row => `${row.name}@${row.version}\nDeclared license: ${row.declaredLicense}\n${row.notices.map(notice => `${notice.file}\n${notice.text}`).join('\n')}\n`).join('\n---\n\n'));
