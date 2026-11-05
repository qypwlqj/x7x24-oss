package com.x7x24.learning;

import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.engine.ProjectRepo;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import com.x7x24.workflow.Canonical;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class LearningStore {
  final Db db; final Clk clk; final ProjectRepo projects;
  @Value("${x7x24.learning.enabled:false}") boolean enabled;
  public LearningStore(Db db, Clk clk, ProjectRepo projects) { this.db=db; this.clk=clk; this.projects=projects; }
  public String scope(String project) {
    String ws=Tenant.id();
    if (!Objects.equals(projects.require(project).workspaceId(),ws)) throw new IllegalArgumentException("项目不属于当前工作区");
    return ws;
  }
  void requireEnabled() { if(!enabled) throw new IllegalArgumentException("学习闭环尚未启用，请先开启 LEARNING_ENABLED"); }
  static final Set<String> TABLES=Set.of("learning_episode","learning_outcome","learning_suite","learning_policy","learning_experience","learning_experiment","learning_trial","learning_release");
  Map<String,Object> get(String table,String project,String id) {
    if(!TABLES.contains(table)) throw new IllegalArgumentException("记录类型不正确");
    var row=db.row("SELECT * FROM "+table+" WHERE workspace_id=? AND project_id=? AND id=?",scope(project),project,id);
    if(row==null) throw new IllegalArgumentException("学习记录不存在或不在当前项目");
    if(row.get("digest")!=null && !digest(Db.str(row.get("body"))).equals(row.get("digest")))throw new IllegalArgumentException("学习记录内容校验未通过");
    return row;
  }
  List<Map<String,Object>> list(String table,String project) {
    if(!TABLES.contains(table)) throw new IllegalArgumentException("记录类型不正确");
    return db.rows("SELECT * FROM "+table+" WHERE workspace_id=? AND project_id=? ORDER BY created_at,id LIMIT 500",scope(project),project);
  }
  static String json(Object value) { return Canonical.json(Json.tree(Redactor.json(value))); }
  static String digest(String json) { return Canonical.digest(Json.tree(json)); }
  static String id() { return U.uuid(); }
  static com.fasterxml.jackson.databind.JsonNode body(Map<String,Object> row) { return Json.tree(Db.str(row.get("body"))); }
  static Map<String,Object> visible(Map<String,Object> row) {
    var result=new LinkedHashMap<>(row); if(row.get("body")!=null)result.put("body",body(row)); return result;
  }
}
