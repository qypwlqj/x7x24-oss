package com.x7x24.unit;

import com.x7x24.integration.StackDetector;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StackDetectorTest {
  static final String FRONT_PKG = """
      {"name":"web","scripts":{"dev":"vite","build":"tsc && vite build","test":"vitest run --passWithNoTests"},
       "dependencies":{"react":"18","react-dom":"18","react-router-dom":"6"},"devDependencies":{"typescript":"5","vite":"5","vitest":"1","tailwindcss":"3"}}""";
  static final String GOMOD = "module intranet-backend\n\ngo 1.23\n\nrequire (\n\tgithub.com/gin-gonic/gin v1.10.0\n\tgorm.io/gorm v1.25.12\n\tgithub.com/go-sql-driver/mysql v1.7.0\n)\n";

  @Test void detectsLmsStyleFullStackRepo() {
    var paths = List.of(".github/workflows/ci.yml", "README.md", "docker-compose.yml", "backend/Dockerfile", "backend/go.mod", "backend/main.go", "backend/internal/handlers/user.go", "backend/internal/handlers/user_test.go",
        "database/migrations/001_initial_schema.sql", "docs/ARCHITECTURE.md", "frontend/Dockerfile", "frontend/nginx.conf", "frontend/.env.production", "frontend/package.json", "frontend/package-lock.json", "frontend/pnpm-lock.yaml",
        "frontend/tsconfig.json", "frontend/src/App.tsx", "frontend/src/index.css", "frontend/src/locales/en/common.json", "frontend/src/pages/A.test.tsx", "frontend/node_modules/x/package.json");
    var files = Map.of("frontend/package.json", FRONT_PKG, "backend/go.mod", GOMOD);
    var s = StackDetector.detect(paths, files::get);
    assertTrue(s.technology().startsWith("前后端分离全栈仓库"), s.technology());
    assertTrue(s.technology().contains("React") && s.technology().contains("Go 1.23") && s.technology().contains("Gin") && s.technology().contains("GORM"), s.technology());
    assertFalse(s.technology().contains("CommonJS"));
    assertEquals(2, s.modules().size());
    var be = s.modules().stream().filter(m -> m.path.equals("backend/")).findFirst().orElseThrow(); assertEquals("cd backend && go test ./...", be.testCommand);
    var fe = s.modules().stream().filter(m -> m.path.equals("frontend/")).findFirst().orElseThrow(); assertEquals("cd frontend && npm test", fe.testCommand); assertEquals("cd frontend && npm run build", fe.buildCommand);
    assertTrue(s.allowedPaths().containsAll(List.of("backend/", "frontend/src/", "docs/")), s.allowedPaths().toString());
    assertFalse(s.allowedPaths().contains("src/"));
    assertTrue(s.allowedExtensions().containsAll(List.of("go", "ts", "tsx", "css", "json", "md")));
    assertTrue(s.pathRules().stream().anyMatch(r -> r.prefix.equals(".github/") && r.level.equals("forbidden")));
    assertTrue(s.pathRules().stream().anyMatch(r -> r.prefix.equals("backend/Dockerfile") && r.level.equals("forbidden")));
    assertTrue(s.pathRules().stream().anyMatch(r -> r.prefix.equals("frontend/.env.production") && r.level.equals("forbidden")));
    assertTrue(s.pathRules().stream().anyMatch(r -> r.prefix.equals("database/migrations/") && r.level.equals("high")));
    assertTrue(s.discoveryRoots().containsAll(List.of("backend/", "frontend/src/")));
    assertEquals("ci.yml", s.workflow());
    assertFalse(s.allowedPaths().stream().anyMatch(p -> p.contains("node_modules")));
  }
  @Test void detectsSingleNodeCommonJsLibrary() {
    var s = StackDetector.detect(List.of("package.json", "src/index.js", "tests/index.test.js", ".github/workflows/test.yml"), Map.of("package.json", "{\"scripts\":{\"test\":\"node --test\"},\"devDependencies\":{}}")::get);
    assertTrue(s.technology().contains("JavaScript (CommonJS)"), s.technology()); assertEquals(List.of("src/", "tests/"), s.allowedPaths());
    assertEquals("npm test", s.modules().get(0).testCommand); assertEquals("test.yml", s.workflow()); assertFalse(s.allowedExtensions().contains("ts"));
  }
  @Test void detectsSpringAndPythonAndSkipsWorkspaceShell() {
    var s = StackDetector.detect(List.of("package.json", "apps/api/pom.xml", "apps/api/src/main/java/A.java", "apps/ml/pyproject.toml", "apps/ml/app/main.py", "packages/ui/package.json", "packages/ui/src/x.ts"),
        Map.of("package.json", "{\"workspaces\":[\"packages/*\"]}", "apps/api/pom.xml", "<artifactId>spring-boot-starter-web</artifactId>", "apps/ml/pyproject.toml", "dependencies=['fastapi']", "packages/ui/package.json", "{\"devDependencies\":{\"typescript\":\"5\"}}")::get);
    assertEquals(3, s.modules().size(), s.modules().stream().map(m -> m.path).toList().toString());
    assertTrue(s.technology().contains("Spring Boot") && s.technology().contains("FastAPI"), s.technology());
    assertTrue(s.allowedPaths().containsAll(List.of("apps/api/src/", "apps/ml/", "packages/ui/src/")));
    assertEquals("cd apps/api && mvn -q test", s.modules().stream().filter(m -> m.path.equals("apps/api/")).findFirst().orElseThrow().testCommand);
  }
  @Test void unknownRepoStillProducesValidConfig() {
    var s = StackDetector.detect(List.of("README.md", "docs/a.md"), p -> null);
    assertFalse(s.allowedPaths().isEmpty()); assertTrue(s.allowedPaths().stream().allMatch(p -> p.endsWith("/")));
  }
}
