FROM node:24.22.1-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY packages/shared/package.json packages/shared/
COPY apps/web/package.json apps/web/
RUN npm ci
COPY packages packages
COPY apps/web apps/web
ARG API_PROXY_TARGET=http://127.0.0.1:8787
ENV API_PROXY_TARGET=$API_PROXY_TARGET NEXT_TELEMETRY_DISABLED=1
RUN npm run build -w @x7x24/web

FROM node:24.22.1-bookworm-slim
WORKDIR /app
RUN useradd -r -u 10001 x7x24
ENV NODE_ENV=production PORT=5173 HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1
COPY --from=build --chown=x7x24:x7x24 /app/apps/web/.next/standalone ./
COPY --from=build --chown=x7x24:x7x24 /app/apps/web/.next/static apps/web/.next/static
COPY --from=build --chown=x7x24:x7x24 /app/apps/web/public apps/web/public
USER x7x24
EXPOSE 5173
CMD ["node", "apps/web/server.js"]
