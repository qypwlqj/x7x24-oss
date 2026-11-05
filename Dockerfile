# Next.js App Router + Java API, retaining the single-container entrypoint.
FROM node:24.22.1-bookworm-slim AS web
WORKDIR /app
COPY package.json package-lock.json ./
COPY packages/shared/package.json packages/shared/
COPY apps/web/package.json apps/web/
RUN npm ci
COPY packages packages
COPY apps/web apps/web
ENV API_PROXY_TARGET=http://127.0.0.1:8788 NEXT_TELEMETRY_DISABLED=1
RUN npm run build -w @x7x24/web

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY apps/server-java/pom.xml .
COPY apps/server-java/src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS java

FROM node:24.22.1-bookworm-slim
WORKDIR /app
COPY --from=java /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN useradd -r -u 10001 x7x24 && mkdir -p /data && chown x7x24 /data
COPY --from=build /build/target/x7x24-server.jar app.jar
COPY --from=web --chown=x7x24:x7x24 /app/apps/web/.next/standalone ./
COPY --from=web --chown=x7x24:x7x24 /app/apps/web/.next/static apps/web/.next/static
COPY --from=web --chown=x7x24:x7x24 /app/apps/web/public apps/web/public
COPY scripts/start-production.sh /app/start-production.sh
ENV PORT=8787 DATA_DIR=/data WEB_DIR=/app/no-web NODE_ENV=production HOSTNAME=0.0.0.0 NEXT_TELEMETRY_DISABLED=1 JAVA_OPTS="-XX:MaxRAMPercentage=60"
USER x7x24
VOLUME /data
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s CMD node -e 'fetch("http://127.0.0.1:"+process.env.PORT+"/api/health").then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))'
ENTRYPOINT ["sh", "/app/start-production.sh"]
