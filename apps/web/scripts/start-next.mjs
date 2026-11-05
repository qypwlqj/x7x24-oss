import { cpSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const serverRoot = join(root, '.next/standalone/apps/web');
if (!existsSync(join(serverRoot, 'server.js'))) throw new Error('Run npm run build before starting the production server');
cpSync(join(root, '.next/static'), join(serverRoot, '.next/static'), { recursive: true });
cpSync(join(root, 'public'), join(serverRoot, 'public'), { recursive: true });
process.env.PORT ||= '5173';
process.env.HOSTNAME ||= '0.0.0.0';
await import(pathToFileURL(join(serverRoot, 'server.js')).href);
