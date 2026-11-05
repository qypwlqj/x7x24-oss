package com.x7x24.learning;

import com.x7x24.tenant.WorkspaceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/learning")
public class LearningApi {
  private final LearningService service;private final EpisodeProjector projector;
  public LearningApi(LearningService service,EpisodeProjector projector){this.service=service;this.projector=projector;}
  @GetMapping public Object overview(@PathVariable String projectId){return service.overview(projectId);}
  @PostMapping("/project") public Object project(@PathVariable String projectId,HttpServletRequest request){WorkspaceFilter.requireManager(request,"整理学习样本");return projector.project(projectId);}
  @PostMapping("/suites") public Object suite(@PathVariable String projectId,@RequestBody Learning.Suite body,HttpServletRequest request){WorkspaceFilter.requireManager(request,"创建评测集");return service.createSuite(projectId,body);}
  public record Proposal(String suiteId,int count) {}
  @PostMapping("/propose") public Object propose(@PathVariable String projectId,@RequestBody Proposal body,HttpServletRequest request){WorkspaceFilter.requireManager(request,"生成策略候选");return service.propose(projectId,body.suiteId(),body.count());}
  public record EvaluationRequest(Learning.Split split) {}
  @PostMapping("/policies/{id}/evaluate") public Object evaluate(@PathVariable String projectId,@PathVariable String id,@RequestBody EvaluationRequest body,HttpServletRequest request){WorkspaceFilter.requireManager(request,"运行策略对照");return service.evaluate(projectId,id,body.split());}
  public record ReleaseRequest(String state,int percentage,String reason) {}
  @PostMapping("/policies/{id}/release") public Object release(@PathVariable String projectId,@PathVariable String id,@RequestBody ReleaseRequest body,HttpServletRequest request){WorkspaceFilter.requireManager(request,"发布或撤销返修策略");return service.release(projectId,id,body.state(),body.percentage(),body.reason());}
}
