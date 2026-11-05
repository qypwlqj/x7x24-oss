package com.x7x24.it;

import com.x7x24.tenant.Tenant;
import com.x7x24.util.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 决策 8A：Postgres 行级安全。只在提供真实 Postgres 时运行（CI 的 postgres 服务；本地设置 X7_PG_TEST_URL）。
 * 连接账号必须是普通用户（不是超级用户），否则 RLS 不生效。
 * 覆盖：迁移在 Postgres 上全部可执行；绕过应用层过滤的裸 SQL 也读不到 / 写不进别的工作区；审计哈希链跨工作区仍然连续。
 */
@EnabledIfEnvironmentVariable(named = "X7_PG_TEST_URL", matches = "jdbc:postgresql:.+")
class PostgresRlsIT extends Harness {
  static final String B = "ws_pg_b";
  @DynamicPropertySource static void pg(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("X7_PG_TEST_URL"));
    r.add("spring.datasource.username", () -> System.getenv().getOrDefault("X7_PG_TEST_USER", "x7app"));
    r.add("spring.datasource.password", () -> System.getenv().getOrDefault("X7_PG_TEST_PASSWORD", "x7app"));
    r.add("x7x24.data-dir", () -> System.getProperty("java.io.tmpdir") + "/x7pg");
  }
  @Autowired TestRestTemplate http; @Autowired DataSource ds;

  @BeforeEach void ensureB() {
    if (db.count("SELECT COUNT(*) FROM workspace WHERE id=?", B) == 0)
      db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)", B, "pg-b", "B", "personal", "{}", "active", clk.iso());
  }
  String post(String ws, String path, Object body) {
    var h = new HttpHeaders(); if (ws != null) h.set("X-Workspace", ws); h.setContentType(MediaType.APPLICATION_JSON);
    var res = http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    assertEquals(200, res.getStatusCode().value(), res.getBody()); return res.getBody();
  }

  @Test void typedWorkflowTablesEnforceRlsScopeAndImmutability() {
    String id=Json.tree(post(null,"/api/projects/sandbox-default/tasks",Map.of("title","typed-rls-fixture","content","synthetic fixture"))).path("taskId").asText();
    String version="rls-artifact-"+System.nanoTime();
    Tenant.run(Tenant.LEGACY,()->db.update("INSERT INTO artifact_version(id,workspace_id,project_id,task_id,artifact_type,schema_version,content_digest,body,created_at) VALUES(?,?, 'sandbox-default',?,'RequirementSpec',1,'fixture','{}',?)",version,Tenant.LEGACY,id,clk.iso()));
    for(String table:java.util.List.of("workflow_plan_revision","node_instance","input_binding","control_dependency","artifact_version","artifact_lineage","artifact_validation","approval_record","artifact_invalidation","node_run","execution_lease","workflow_candidate","coding_job","coding_job_event","context_manifest","skill_evaluation","skill_release_revocation"))assertEquals(Boolean.TRUE,db.row("SELECT relforcerowsecurity FROM pg_class WHERE relname=?",table).get("relforcerowsecurity"),table);
    Tenant.run(B,()->{
      assertEquals(0,db.count("SELECT COUNT(*) FROM artifact_version WHERE id=?",version));
      assertThrows(Exception.class,()->db.update("INSERT INTO artifact_validation(id,workspace_id,version_id,profile,result,checked_digest,body,created_at) VALUES(?,?,?,'schema-v1','PASS','fixture','{}',?)","cross-"+System.nanoTime(),B,version,clk.iso()));
    });
    Tenant.run(Tenant.LEGACY,()->assertThrows(Exception.class,()->db.update("UPDATE artifact_version SET content_digest='modified' WHERE id=?",version)));
  }

  @Test void executionFactsAndCandidateSnapshotsStayScopedAndAppendOnly() {
    String task=Json.tree(post(null,"/api/projects/sandbox-default/tasks",Map.of("title","local-job-rls-"+System.nanoTime(),"content","synthetic"))).path("taskId").asText();
    String key="scope-"+System.nanoTime();String ws=Tenant.LEGACY;String now=clk.iso();
    Tenant.run(ws,()->{
      db.update("INSERT INTO workflow_plan_revision(id,workspace_id,project_id,task_id,revision_no,plan_hash,body,status,created_at) VALUES(?,?,'sandbox-default',?,1,'fixture','{}','VALIDATED',?)",key,ws,task,now);
      db.update("INSERT INTO node_instance(id,plan_revision_id,workspace_id,project_id,task_id,definition_ref,body) VALUES('node',?,?,'sandbox-default',?,'local.file-check@1','{}')",key,ws,task);
      db.update("INSERT INTO node_run(id,workspace_id,project_id,task_id,plan_revision_id,node_id,attempt_no,input_fingerprint,inputs,role_snapshot,state,owner_id,epoch,started_at,heartbeat_at) VALUES(?,?,'sandbox-default',?,?,'node',1,'fixture','[]','{}','SUCCEEDED','fixture',1,?,?)",key,ws,task,key,now,now);
      db.update("INSERT INTO coding_job(id,workspace_id,task_id,node_run_id,operation_key,request_digest,state,spec,created_at,updated_at) VALUES(?,?,?,?,?,'fixture','SUCCEEDED','{}',?,?)",key,ws,task,key,key,now,now);
      db.update("INSERT INTO coding_job_event(job_id,seq,workspace_id,event_type,body,created_at) VALUES(?,1,?,'fixture','{}',?)",key,ws,now);
      db.update("INSERT INTO workflow_candidate(id,workspace_id,project_id,task_id,request_key,request_digest,expected_task_version,body,provenance,created_at) VALUES(?,?,'sandbox-default',?,?,'fixture',0,'{}','FIXTURE',?)",key,ws,task,key,now);
      db.update("INSERT INTO context_manifest(id,workspace_id,task_id,node_run_id,digest,body,created_at) VALUES(?,?,?,?,'fixture','{}',?)",key,ws,task,key,now);
      db.update("INSERT INTO skill_evaluation(id,workspace_id,task_id,node_run_id,set_digest,body,created_at) VALUES(?,?,?,?,'fixture','{}',?)",key,ws,task,key,now);
      db.update("INSERT INTO skill_release_revocation(workspace_id,skill_ref,reason,actor,created_at) VALUES(?,?,'fixture','fixture',?)",ws,key,now);
      for(String table:java.util.List.of("workflow_candidate","coding_job_event","context_manifest","skill_evaluation"))assertThrows(Exception.class,()->db.update("UPDATE "+table+" SET body='modified' WHERE workspace_id=?",ws),table);
      assertThrows(Exception.class,()->db.update("DELETE FROM skill_release_revocation WHERE workspace_id=? AND skill_ref=?",ws,key));
    });
    Tenant.run(B,()->{
      for(String table:java.util.List.of("workflow_candidate","coding_job","context_manifest","skill_evaluation"))assertEquals(0,db.count("SELECT COUNT(*) FROM "+table+" WHERE id=?",key),table);
      assertEquals(0,db.count("SELECT COUNT(*) FROM coding_job_event WHERE job_id=?",key));assertEquals(0,db.count("SELECT COUNT(*) FROM skill_release_revocation WHERE skill_ref=?",key));
      assertThrows(Exception.class,()->db.update("INSERT INTO coding_job_event(job_id,seq,workspace_id,event_type,body,created_at) VALUES(?,2,?,'cross','{}',?)",key,B,now));
      assertThrows(Exception.class,()->db.update("INSERT INTO context_manifest(id,workspace_id,task_id,node_run_id,digest,body,created_at) VALUES(?,?,?,?,'cross','{}',?)",key+"-cross",B,task,key,now));
      assertThrows(Exception.class,()->db.update("INSERT INTO skill_evaluation(id,workspace_id,task_id,node_run_id,set_digest,body,created_at) VALUES(?,?,?,?,'cross','{}',?)",key+"-cross",B,task,key,now));
    });
  }

  @Test void notSuperuser() {
    assertEquals(Boolean.FALSE, db.row("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").get("rolsuper"), "测试账号是超级用户时 RLS 不生效，测试没有意义");
    assertEquals(Boolean.TRUE, db.row("SELECT relforcerowsecurity FROM pg_class WHERE relname='task'").get("relforcerowsecurity"));
  }

  @Test void rawSqlInOtherWorkspaceSeesNothing() {
    String marker = "PG-A-" + System.nanoTime();
    String tid = Json.tree(post(null, "/api/projects/sandbox-default/tasks", Map.of("title", marker, "content", marker))).path("taskId").asText();
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM task WHERE id=?", tid).get("workspace_id"));
    // B 上下文：不带任何 workspace 条件的裸 SQL
    Tenant.run(B, () -> {
      assertEquals(0, db.count("SELECT COUNT(*) FROM task WHERE id=?", tid), "RLS 应挡住 B 读 A 的任务");
      assertEquals(0, db.count("SELECT COUNT(*) FROM product_line WHERE workspace_id=?", Tenant.LEGACY));
      assertEquals(0, db.count("SELECT COUNT(*) FROM project WHERE id='sandbox-default'"));
      assertEquals(0, db.count("SELECT COUNT(*) FROM signal WHERE task_id=?", tid));
      assertEquals(0, db.count("SELECT COUNT(*) FROM task_event WHERE task_id=?", tid));
      assertEquals(0, db.update("UPDATE task SET title='hacked' WHERE id=?", tid), "RLS 应挡住 B 改 A 的任务");
      assertThrows(Exception.class, () -> db.update("INSERT INTO budget_ledger(day,occurred_at,workspace_id) VALUES('2026-01-01','x',?)", Tenant.LEGACY), "RLS 应挡住 B 往 A 写数据");
    });
    // A 上下文能看到；系统上下文看到全部
    Tenant.run(Tenant.LEGACY, () -> assertEquals(1, db.count("SELECT COUNT(*) FROM task WHERE id=?", tid)));
    assertEquals(1, db.count("SELECT COUNT(*) FROM task WHERE id=?", tid));
    assertNotEquals("hacked", repo.get(tid).title);
  }

  @Test void chatTablesIsolated() {
    String cid = Json.tree(post(null, "/api/chats", Map.of())).path("id").asText();
    assertEquals(Tenant.LEGACY, db.row("SELECT workspace_id FROM chat_session WHERE id=?", cid).get("workspace_id"));
    Tenant.run(B, () -> {
      assertEquals(0, db.count("SELECT COUNT(*) FROM chat_session WHERE id=?", cid), "RLS 应挡住 B 读 A 的会话");
      assertEquals(0, db.count("SELECT COUNT(*) FROM chat_draft WHERE session_id=?", cid));
      assertThrows(Exception.class, () -> db.update("INSERT INTO chat_message(session_id,workspace_id,role,content,created_at) VALUES(?,?,?,?,?)", cid, Tenant.LEGACY, "user", "x", "2026-01-01"));
    });
  }

  @Test void platformKillSwitchVisibleEverywhere() {
    db.update("INSERT INTO kill_switch(id,scope,mode,source,trigger_key,reason,active,created_at,workspace_id) VALUES(?,?,?,?,?,?,0,?,?)", "pg-kill-" + System.nanoTime(), "global", "freeze", "auto", "audit:chain", "t", clk.iso(), Tenant.PLATFORM);
    Tenant.run(B, () -> assertTrue(db.count("SELECT COUNT(*) FROM kill_switch WHERE workspace_id='_platform'") > 0));
  }

  @Test void auditChainStaysContinuousAcrossWorkspaces() {
    long before = Tenant.run(B, () -> audit.count());
    Tenant.run(B, () -> audit.sys("PG_TEST_B", "test", Map.of("n", 1), null));
    post(null, "/api/projects/sandbox-default/tasks", Map.of("title", "chain", "content", "c"));
    Tenant.run(B, () -> audit.sys("PG_TEST_B", "test", Map.of("n", 2), null));
    var v = Tenant.run(B, () -> audit.verify());
    assertEquals(true, v.get("ok"), v.toString());
    assertEquals(db.count("SELECT COUNT(*) FROM task_event"), ((Number) v.get("checked")).longValue());
    Tenant.run(B, () -> assertEquals(before + 2, audit.count(), "B 只看到自己的审计"));
  }

  @Test void engineRunsOnPostgres() {
    String tid = ingestIssue("pg engine " + System.nanoTime(), "make it work");
    var t = run(tid, x -> x.st() != com.x7x24.core.Status.BACKLOG, 20);
    assertNotNull(t); assertEquals(Tenant.LEGACY, t.workspaceId);
  }
}
