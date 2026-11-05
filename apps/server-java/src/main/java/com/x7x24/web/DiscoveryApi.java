package com.x7x24.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.engine.Discovery;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/discovery")
public class DiscoveryApi {
  private final Discovery discovery;
  public DiscoveryApi(Discovery discovery) { this.discovery = discovery; }
  @GetMapping("/candidates") public Object candidates(@PathVariable String projectId) { return discovery.candidates(projectId); }
  @PostMapping("/scan") public Object scan(@PathVariable String projectId) { return discovery.scan(projectId); }
  @PostMapping("/logs") public Object logs(@PathVariable String projectId, @RequestBody JsonNode body) { return Map.of("candidateId", discovery.importLog(projectId, body.path("title").asText("导入错误日志"), body.path("log").asText())); }
  @PostMapping("/candidates/{id}/tasks") public Object promote(@PathVariable String projectId, @PathVariable String id) { return discovery.promote(projectId, id); }
  @PutMapping("/candidates/{id}/dismiss") public Object dismiss(@PathVariable String projectId, @PathVariable String id) { discovery.dismiss(projectId, id); return Map.of("ok", true); }
}
