# 仅后端 API（前端由独立 Next.js 服务运行）。构建上下文：apps/server-java
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# Build only required artifacts; keep dependency cache and propagate download/build errors.
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 x7x24 && mkdir -p /data && chown x7x24 /data
COPY --from=build /build/target/x7x24-server.jar app.jar
# WEB_DIR 指向不存在的目录 → 不托管前端；Render 免费实例 512MB，堆按比例限制
ENV PORT=8787 DATA_DIR=/data WEB_DIR=/app/no-web JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
USER x7x24
EXPOSE 8787
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
