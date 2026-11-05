package com.x7x24.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.auth.AuthService;
import com.x7x24.core.Status;
import com.x7x24.db.Db;
import com.x7x24.engine.*;
import com.x7x24.notification.*;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.springframework.test.context.TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:notificationtests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE;DB_CLOSE_DELAY=-1")
class NotificationsIT extends Harness {
  @Autowired NotificationService notifications; @Autowired NotificationWorker worker; @Autowired NotificationEvents events;
  @Autowired AuthService auth; @Autowired TestRestTemplate http;
  @MockitoBean NotificationMail mail;
  record User(String id, String token, String email) {}
  @BeforeEach void notificationSetup() {
    db.update("DELETE FROM notification_delivery"); db.update("DELETE FROM notification_event"); db.update("DELETE FROM notification_preference");
    db.update("UPDATE task SET feedback_done=1,next_run_at=NULL WHERE status='RELEASED'");
    when(mail.ready()).thenReturn(true); when(mail.taskUrl(anyString())).thenAnswer(a -> "http://localhost/t/" + a.getArgument(0));
  }
  User user(String ws, String role) {
    long n = System.nanoTime(); String id = "un" + n, login = "n" + n;
    db.update("INSERT INTO app_user(id,github_id,login,created_at,last_login_at) VALUES(?,?,?,?,?)", id, n, login, clk.iso(), clk.iso());
    db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)", ws, id, role, clk.iso());
    return new User(id, auth.issueSession(new AuthService.User(id,n,login,null,null)), login + "@example.invalid");
  }
  String workspace() { String id = "wn" + System.nanoTime(); db.update("INSERT INTO workspace(id,slug,name,kind,plan_quota,status,created_at) VALUES(?,?,?,?,?,?,?)",id,id,id,"team","{}","active",clk.iso()); return id; }
  JsonNode call(String ws, User user, HttpMethod method, String path, Object body) {
    var h = new HttpHeaders(); h.set("X-Workspace",ws); if (user != null) h.setBearerAuth(user.token); h.setContentType(MediaType.APPLICATION_JSON);
    var r = http.exchange("/api/me/notifications" + path,method,new HttpEntity<>(body,h),String.class);
    var n = Json.M.createObjectNode(); n.put("status",r.getStatusCode().value()); n.set("data",Json.tree(r.getBody())); return n;
  }
  Map<String,Object> prefs(boolean email) { return new LinkedHashMap<>(Map.of("emailEnabled",email,"inAppEnabled",true,"scope","workspace","projectIds",List.of(),"events",NotificationService.DEFAULT_EVENTS)); }
  void verified(User u) { notifications.saveEmail(u.id,u.email); db.update("UPDATE user_notification_email SET verified_at=? WHERE user_id=?",clk.iso(),u.id); }
  void subscribe(String ws, User u, boolean email) { Tenant.run(ws, () -> notifications.saveSettings(ws,u.id,email,true,"workspace",List.of(),NotificationService.DEFAULT_EVENTS)); }
  Task task() { return repo.get(ingestIssue("通知测试 slugify " + java.util.UUID.randomUUID(), PipelineIT.SLUG)); }
  void event(Task t, String kind, String key) { events.task(t,kind,key,"测试通知内容"); }

  @Test void verifiesEmailThroughApiWithoutExposingCodeOrSenderCredentials() {
    String ws = workspace(); User u = user(ws,"viewer");
    assertEquals(403,call(ws,null,HttpMethod.GET,"/settings",null).path("status").asInt());
    assertEquals(200,call(ws,u,HttpMethod.PUT,"/email",Map.of("email",u.email)).path("status").asInt());
    assertEquals(400,call(ws,u,HttpMethod.PUT,"/settings",prefs(true)).path("status").asInt());
    JsonNode sent = call(ws,u,HttpMethod.POST,"/email/send-code",null); assertEquals(200,sent.path("status").asInt(),sent.toString());
    var queued = db.row("SELECT * FROM notification_delivery WHERE user_id=?",u.id);
    assertNull(queued.get("message")); assertTrue(Db.str(queued.get("body_cipher")).startsWith("v1."));
    worker.process(); var body = ArgumentCaptor.forClass(String.class); verify(mail).send(eq(u.email),contains("验证"),body.capture());
    var m = Pattern.compile("验证码是：(\\d{6})").matcher(body.getValue()); assertTrue(m.find()); String code=m.group(1);
    assertFalse(sent.toString().contains(code));
    String wrong = code.equals("000000") ? "000001" : "000000";
    assertEquals(400,call(ws,u,HttpMethod.POST,"/email/verify",Map.of("code",wrong)).path("status").asInt());
    assertEquals(1,Db.i(notifications.address(u.id).get("code_attempts")),"wrong attempts must persist despite HTTP error");
    var valid = call(ws,u,HttpMethod.POST,"/email/verify",Map.of("code",code)); assertTrue(valid.path("data").path("verified").asBoolean());
    assertEquals(400,call(ws,u,HttpMethod.POST,"/email/verify",Map.of("code",code)).path("status").asInt());
    assertEquals(200,call(ws,u,HttpMethod.PUT,"/settings",prefs(true)).path("status").asInt(),"viewer can manage own preferences");
    var history=call(ws,u,HttpMethod.GET,"?history=true",null).toString(); assertFalse(history.contains(code)); assertFalse(history.contains("body_cipher")); assertFalse(history.contains("code_hash"));
    assertNull(db.row("SELECT body_cipher FROM notification_delivery WHERE user_id=?",u.id).get("body_cipher"));
  }

  @Test void validatesAddressesAndLimitsCodeAttemptsResendsAndExpiry() {
    String ws=workspace(); User u=user(ws,"member");
    for (String email : List.of("nope","a@example.invalid\r\nBcc: b@example.invalid","a@example.invalid,b@example.invalid","Name <a@example.invalid>")) assertThrows(IllegalArgumentException.class,()->notifications.saveEmail(u.id,email));
    notifications.saveEmail(u.id,u.email); notifications.sendCode(ws,u.id);
    assertThrows(IllegalArgumentException.class,()->notifications.sendCode(ws,u.id));
    db.update("UPDATE user_notification_email SET code_hash=? WHERE user_id=?","x".repeat(64),u.id);
    for(int i=0;i<5;i++) assertThrows(IllegalArgumentException.class,()->notifications.verify(u.id,"123456"));
    assertTrue(assertThrows(IllegalArgumentException.class,()->notifications.verify(u.id,"123456")).getMessage().contains("次数"));
    for(int i=0;i<4;i++) { clk.advance(U.MIN); notifications.saveEmail(u.id,"recipient"+i+"@example.invalid"); notifications.sendCode(ws,u.id); }
    clk.advance(U.MIN); assertThrows(IllegalArgumentException.class,()->notifications.sendCode(ws,u.id),"address changes must not reset account limits");
    clk.advance(11*U.MIN); worker.process(); verify(mail,never()).send(anyString(),anyString(),anyString());
    assertEquals(0,db.count("SELECT COUNT(*) FROM notification_delivery WHERE user_id=? AND state<>'SKIPPED'",u.id));
  }

  @Test void preferencesAndInboxAreIsolatedByUserWorkspaceAndSelectedProject() {
    String a=Tenant.LEGACY,b=workspace(); User u=user(a,"viewer"),other=user(a,"member");
    db.update("INSERT INTO workspace_member(workspace_id,user_id,role,created_at) VALUES(?,?,?,?)",b,u.id,"viewer",clk.iso());
    Task task=task();
    var scoped=prefs(false); scoped.put("scope","projects"); scoped.put("projectIds",List.of(task.projectId));
    assertEquals(200,call(a,u,HttpMethod.PUT,"/settings",scoped).path("status").asInt());
    assertEquals(400,call(b,u,HttpMethod.PUT,"/settings",scoped).path("status").asInt(),"foreign projects rejected");
    assertEquals("workspace",call(b,u,HttpMethod.GET,"/settings",null).path("data").path("scope").asText());
    event(task,"completed","isolated"); worker.process();
    var inbox=call(a,u,HttpMethod.GET,"",null).path("data"); assertEquals(1,inbox.path("unread").asInt());
    String id=inbox.path("items").get(0).path("id").asText();
    assertEquals(0,call(a,other,HttpMethod.GET,"",null).path("data").path("items").size());
    assertEquals(0,call(b,u,HttpMethod.GET,"",null).path("data").path("items").size());
    assertEquals(404,call(a,other,HttpMethod.PUT,"/"+id+"/read",null).path("status").asInt());
    assertEquals(404,call(b,u,HttpMethod.PUT,"/"+id+"/read",null).path("status").asInt());
    assertEquals(200,call(a,u,HttpMethod.PUT,"/all/read",null).path("status").asInt());
    assertEquals(0,call(a,u,HttpMethod.GET,"",null).path("data").path("unread").asInt());
    assertEquals(404,call(b,other,HttpMethod.GET,"/settings",null).path("status").asInt());
  }

  @Test void lifecycleDistinguishesPublicationAndFinalCompletionAndDoesNotRepeat() {
    User u=user(Tenant.LEGACY,"owner"); subscribe(Tenant.LEGACY,u,false);
    String id=task().id;
    Task released=runTo(id,Status.RELEASED); assertEquals(Status.RELEASED,released.st());
    assertEquals(1,db.count("SELECT COUNT(*) FROM notification_event WHERE task_id=? AND kind='published'",id));
    assertEquals(0,db.count("SELECT COUNT(*) FROM notification_event WHERE task_id=? AND kind='completed'",id));
    worker.process(); assertEquals(0,db.count("SELECT COUNT(*) FROM notification_delivery WHERE task_id=? AND user_id=?",id,u.id),"publication is opt-in");
    Task finished=run(id,t->t.feedbackDone!=0,30); assertEquals(1,finished.feedbackDone);
    worker.process(); worker.process();
    assertEquals(1,db.count("SELECT COUNT(*) FROM notification_delivery WHERE task_id=? AND user_id=? AND kind='completed'",id,u.id));
    engine.tick(); assertEquals(1,db.count("SELECT COUNT(*) FROM notification_event WHERE task_id=? AND kind='completed'",id));
    var row=db.row("SELECT message,state FROM notification_delivery WHERE task_id=? AND user_id=?",id,u.id); assertEquals("IN_APP",row.get("state")); assertTrue(Db.str(row.get("message")).contains("Release"));
  }

  @Test void anomalyAndOwnerEventsAreDurableAndAuditReplayDoesNotDuplicate() {
    User u=user(Tenant.LEGACY,"owner"); subscribe(Tenant.LEGACY,u,false); Task t=runTo(task().id,Status.DEV);
    assertEquals(Status.DEV, t.st(), () -> "Expected DEV before injecting anomaly: " + com.x7x24.util.Json.str(t) + " audit=" + audit.list(t.id,null,12,0));
    engine.apply(t,Decision.anomaly("ci_broken","测试服务故障"));
    worker.process(); assertEquals(1,db.count("SELECT COUNT(*) FROM notification_delivery WHERE task_id=? AND kind='anomaly'",t.id));
    audit.task(t.id,t.currentIteration,"OWNER_NEEDED","test",Map.of("detail","请修复 CI"),t.id+":owner:v"+t.version);
    audit.task(t.id,t.currentIteration,"OWNER_NEEDED","test",Map.of("detail","请修复 CI"),t.id+":owner:v"+t.version);
    worker.process(); assertEquals(1,db.count("SELECT COUNT(*) FROM notification_delivery WHERE task_id=? AND kind='action_required'",t.id));
    assertThrows(IllegalStateException.class,()->db.tx(()->{ event(t,"completed","rolled-back-event"); throw new IllegalStateException("rollback"); }));
    assertEquals(0,db.count("SELECT COUNT(*) FROM notification_event WHERE id='rolled-back-event'"));
  }

  @Test void retriesPersistAndRecoverLeasesWithoutDuplicatingInboxItems() {
    User u=user(Tenant.LEGACY,"owner"); verified(u); subscribe(Tenant.LEGACY,u,true); Task t=task();
    doThrow(new IllegalStateException("private-smtp-secret")).doNothing().when(mail).send(anyString(),anyString(),anyString());
    event(t,"completed","retry-event"); worker.process();
    var d=db.row("SELECT * FROM notification_delivery WHERE user_id=?",u.id); assertEquals("RETRY",d.get("state")); assertEquals(1,Db.i(d.get("attempts"))); assertFalse(Db.str(d.get("last_error")).contains("private-smtp-secret"));
    // Simulate an expired claim left by a terminated worker.
    db.update("UPDATE notification_delivery SET state='SENDING',lease_until=? WHERE id=?",Clk.iso(clk.now()-1),d.get("id"));
    clk.advance(2*U.MIN); worker.process(); worker.process();
    d=db.row("SELECT * FROM notification_delivery WHERE user_id=?",u.id); assertEquals("SENT",d.get("state")); assertEquals(2,Db.i(d.get("attempts"))); assertEquals(1,db.count("SELECT COUNT(*) FROM notification_delivery WHERE user_id=?",u.id));
  }

  @Test void stopsAfterFiveFailuresAndKeepsInAppNotificationsWithoutSmtp() {
    User u=user(Tenant.LEGACY,"owner"); verified(u); subscribe(Tenant.LEGACY,u,true); Task t=task();
    when(mail.ready()).thenReturn(false); event(t,"completed","smtp-off"); worker.process();
    assertEquals(1,db.count("SELECT COUNT(*) FROM notification_delivery WHERE user_id=? AND state='PENDING' AND attempts=0 AND in_app=1",u.id));
    when(mail.ready()).thenReturn(true); doThrow(new IllegalStateException("offline")).when(mail).send(anyString(),anyString(),anyString());
    for(int i=0;i<6;i++) { worker.process(); clk.advance(2*U.HOUR); }
    assertEquals("FAILED",db.row("SELECT state FROM notification_delivery WHERE user_id=?",u.id).get("state"));
    verify(mail,times(5)).send(anyString(),anyString(),anyString());
  }

  @Test void cancelsQueuedMailWhenAddressSubscriptionOrMembershipChanges() {
    User changed=user(Tenant.LEGACY,"owner"),unsubscribed=user(Tenant.LEGACY,"member"),removed=user(Tenant.LEGACY,"member");
    for(User u:List.of(changed,unsubscribed,removed)) { verified(u); subscribe(Tenant.LEGACY,u,true); }
    when(mail.ready()).thenReturn(false); Task t=task(); event(t,"completed","cancel-queued"); worker.process();
    notifications.saveEmail(changed.id,"new-address@example.invalid"); subscribe(Tenant.LEGACY,unsubscribed,false);
    db.update("DELETE FROM workspace_member WHERE workspace_id=? AND user_id=?",Tenant.LEGACY,removed.id);
    when(mail.ready()).thenReturn(true); worker.process();
    assertEquals(3,db.count("SELECT COUNT(*) FROM notification_delivery WHERE event_id='cancel-queued' AND state='SKIPPED'"));
    verify(mail,never()).send(anyString(),anyString(),anyString());
  }

  @Test void aCrashDuringTheFifthAttemptDoesNotPermitASixthSend() {
    User u=user(Tenant.LEGACY,"owner"); verified(u); subscribe(Tenant.LEGACY,u,true); Task t=task();
    when(mail.ready()).thenReturn(false); event(t,"completed","fifth-attempt-crash"); worker.process();
    db.update("UPDATE notification_delivery SET state='SENDING',attempts=5,lease_until=? WHERE event_id='fifth-attempt-crash'",Clk.iso(clk.now()-1));
    when(mail.ready()).thenReturn(true); worker.process();
    assertEquals("FAILED",db.row("SELECT state FROM notification_delivery WHERE event_id='fifth-attempt-crash'").get("state"));
    verify(mail,never()).send(anyString(),anyString(),anyString());
  }
}
