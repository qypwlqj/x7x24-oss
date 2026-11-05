package com.x7x24.unit;

import com.x7x24.chat.PlanService;
import com.x7x24.engine.ProjectCfg;
import com.x7x24.pipeline.Nodes;
import com.x7x24.pipeline.StackProfiles;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StackProfilesTest {
  static ProjectCfg.Module mod(String path, String lang) { var m = new ProjectCfg.Module(); m.path = path; m.language = lang; return m; }
  static ProjectCfg lms() {
    var c = new ProjectCfg(); c.technology = "Go (Gin) + TypeScript (React, Vite) + PostgreSQL";
    c.modules = new ArrayList<>(List.of(mod("backend/", "Go (Gin)"), mod("frontend/", "TypeScript (React, Vite)"), mod("server/", "TypeScript (Node, Express)")));
    return c;
  }
  @Test void derivesStacksFromModules() {
    var p = StackProfiles.of(lms());
    assertEquals("auto", p.source);
    assertTrue(p.stacks.containsAll(List.of("go", "react", "node", "sql")), p.stacks.toString());
    assertEquals("go", StackProfiles.stackOfPath(p, "backend/internal/x.go"));
    assertEquals("react", StackProfiles.stackOfPath(p, "frontend/src/a.ts"));
    assertNull(StackProfiles.stackOfPath(p, "scripts/a.sh"));
  }
  @Test void manualProfileWinsAndUnknownStacksDropped() {
    var c = lms(); c.stackProfile = new ProjectCfg.StackProfile(); c.stackProfile.source = "manual";
    var m = new ProjectCfg.ModuleStack(); m.path = "api"; m.stack = "python"; var bad = new ProjectCfg.ModuleStack(); bad.path = "x/"; bad.stack = "cobol";
    c.stackProfile.modules = new ArrayList<>(List.of(m, bad));
    var p = StackProfiles.of(c);
    assertEquals(List.of("python"), p.stacks); assertEquals("api/", p.modules.get(0).path);
  }
  @Test void profileDecidesAmbiguousTsFiles() {
    var p = StackProfiles.of(lms());
    assertEquals(List.of("backend"), PlanService.labels(List.of("server/routes/user.ts"), p));
    assertEquals(List.of("frontend"), PlanService.labels(List.of("frontend/src/api.ts"), p));
    assertEquals(List.of("frontend", "backend", "migration"), PlanService.labels(List.of("frontend/src/App.tsx", "backend/a.go", "backend/migrations/1.sql"), p));
    assertEquals(List.of("node", "react", "go"), PlanService.stacksOf(List.of("server/a.ts", "frontend/b.tsx", "backend/c.go"), p));
  }
  @Test void nodeCatalogFiltersByStackAndPolicy() {
    var c = lms();
    Set<String> ids = new HashSet<>(); for (var d : Nodes.allowed(c)) ids.add(d.id);
    assertTrue(ids.containsAll(List.of("PLANNING", "API_DESIGN", "DB_SCHEMA", "DEV", "QA", "SEC_AUDIT", "ACCEPTANCE")), ids.toString());
    assertFalse(ids.contains("PERF_BUDGET"), "未提供执行器的节点不允许");
    c.nodePolicy.disabled.add("SEC_AUDIT");
    var v = Nodes.view(c).stream().filter(x -> "SEC_AUDIT".equals(x.get("id"))).findFirst().orElseThrow();
    assertEquals(false, v.get("allowed")); assertEquals("项目已禁用", v.get("why"));
    var docs = new ProjectCfg(); docs.modules = new ArrayList<>(List.of(mod("docs/", "Markdown")));
    Set<String> d2 = new HashSet<>(); for (var d : Nodes.allowed(docs)) d2.add(d.id);
    assertFalse(d2.contains("DB_SCHEMA"), d2.toString()); assertTrue(d2.contains("CHECK_MD"));
  }
}
