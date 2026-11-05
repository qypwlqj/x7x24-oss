import type { NextConfig } from 'next';
import path from 'node:path';

const config: NextConfig = {
  output: 'standalone',
  outputFileTracingRoot: path.join(process.cwd(), '../..'),
  outputFileTracingIncludes: { '/legal/*': ['./src/legal/*.md'] },
  basePath: process.env.NEXT_PUBLIC_BASE_PATH ?? '',
  poweredByHeader: false,
  reactStrictMode: true,
  async rewrites() {
    const target = (process.env.API_PROXY_TARGET ?? 'http://127.0.0.1:8787').replace(/\/+$/, '');
    return [{ source: '/api/:path*', destination: `${target}/api/:path*` }];
  },
  async headers() {
    return [{ source: '/:path(pricing|legal/.*)', headers: [{ key: 'X-Robots-Tag', value: 'noindex, nofollow' }] }];
  },
};
export default config;
