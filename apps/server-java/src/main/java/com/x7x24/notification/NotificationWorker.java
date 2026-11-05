package com.x7x24.notification;

import com.x7x24.db.Db;
import com.x7x24.integration.CredentialProvider;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 持久事件扇出 + 有租约的投递队列；独立线程避免阻塞引擎的定时调度器。 */
@Component
public class NotificationWorker {
  private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
  private final Db db; private final Clk clk; private final NotificationService service; private final NotificationMail mail; private final CredentialProvider credentials;
  private final boolean enabled; private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "notification-delivery"); t.setDaemon(true); return t; });
  public NotificationWorker(Db db, Clk clk, NotificationService service, NotificationMail mail, CredentialProvider credentials, @Value("${x7x24.notifications.worker-enabled:true}") boolean enabled) {
    this.db = db; this.clk = clk; this.service = service; this.mail = mail; this.credentials = credentials; this.enabled = enabled;
  }
  @Scheduled(fixedDelay = 2000, initialDelay = 5000) public void poll() {
    if (!enabled || !running.compareAndSet(false, true)) return;
    executor.submit(() -> { try { process(); } catch (Exception e) { log.warn("notification worker failed ({})", e.getClass().getSimpleName()); } finally { running.set(false); } });
  }
  @jakarta.annotation.PreDestroy public void close() { executor.shutdownNow(); }

  public synchronized void process() {
    // Explicit system context: workers iterate workspaces, then re-enter each tenant.
    Tenant.run(Tenant.PLATFORM, () -> {
      var events = db.rows("SELECT id,workspace_id FROM notification_event WHERE materialized_at IS NULL AND (lease_until IS NULL OR lease_until<=?) ORDER BY created_at,id LIMIT 50", clk.iso());
      for (var event : events) Tenant.run(Db.str(event.get("workspace_id")), () -> materialize(Db.str(event.get("id"))));
      var pending = db.rows("SELECT id,workspace_id FROM notification_delivery WHERE state IN ('PENDING','RETRY','SENDING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,id LIMIT 20", clk.iso(), clk.iso());
      for (var delivery : pending) Tenant.run(Db.str(delivery.get("workspace_id")), () -> deliver(Db.str(delivery.get("id"))));
    });
  }
  private void materialize(String id) {
    db.tx(() -> {
      if (db.update("UPDATE notification_event SET lease_until=? WHERE id=? AND materialized_at IS NULL AND (lease_until IS NULL OR lease_until<=?)", Clk.iso(clk.now() + U.MIN), id, clk.iso()) == 0) return;
      var e = db.row("SELECT * FROM notification_event WHERE id=?", id);
      String ws = Db.str(e.get("workspace_id")), project = Db.str(e.get("project_id")), kind = Db.str(e.get("kind"));
      var recipients = db.rows("SELECT p.* FROM notification_preference p JOIN workspace_member m ON m.workspace_id=p.workspace_id AND m.user_id=p.user_id JOIN workspace w ON w.id=p.workspace_id WHERE p.workspace_id=? AND w.status='active'", ws);
      for (var p : recipients) {
        if (!NotificationService.events(p).getOrDefault(kind, false) || !NotificationService.includesProject(p, project)) continue;
        boolean inApp = Db.i(p.get("in_app_enabled")) != 0;
        var a = service.address(Db.str(p.get("user_id")));
        String email = Db.i(p.get("email_enabled")) != 0 && a != null && a.get("verified_at") != null ? Db.str(a.get("email")) : null;
        if (!inApp && email == null) continue;
        String message = Db.str(e.get("message"));
        String url = mail.taskUrl(Db.str(e.get("task_id"))); if (!url.isEmpty()) message += "\n\n查看任务：" + url;
        service.queue(ws, Db.str(p.get("user_id")), id, project, Db.str(e.get("task_id")), kind, "TASK", Db.str(e.get("subject")), message, null, null, email, inApp, email == null ? "IN_APP" : "PENDING");
      }
      db.update("UPDATE notification_event SET materialized_at=?,lease_until=NULL WHERE id=?", clk.iso(), id);
    });
  }
  private boolean member(Map<String,Object> d) {
    return db.count("SELECT COUNT(*) FROM workspace_member m JOIN workspace w ON w.id=m.workspace_id WHERE m.workspace_id=? AND m.user_id=? AND w.status='active'", d.get("workspace_id"), d.get("user_id")) != 0;
  }
  private boolean allowed(Map<String,Object> d) {
    if (!member(d)) return false;
    var a = service.address(Db.str(d.get("user_id")));
    if (a == null || !Objects.equals(a.get("email"), d.get("email"))) return false;
    if ("VERIFY".equals(d.get("purpose"))) return Objects.equals(d.get("code_hash"), a.get("code_hash")) && a.get("code_expires_at") != null && Clk.parse(Db.str(a.get("code_expires_at"))) > clk.now();
    if (a.get("verified_at") == null) return false;
    if ("TEST".equals(d.get("purpose"))) return true;
    var p = db.row("SELECT * FROM notification_preference WHERE workspace_id=? AND user_id=?", d.get("workspace_id"), d.get("user_id"));
    return p != null && Db.i(p.get("email_enabled")) != 0 && NotificationService.events(p).getOrDefault(Db.str(d.get("kind")), false) && NotificationService.includesProject(p, Db.str(d.get("project_id")))
      && db.count("SELECT COUNT(*) FROM task WHERE id=? AND workspace_id=? AND deleted_at IS NULL", d.get("task_id"), d.get("workspace_id")) > 0;
  }
  private void deliver(String id) {
    // A terminated process can leave the fifth attempt claimed without recording its result.
    if (db.update("UPDATE notification_delivery SET state='FAILED',lease_until=NULL,body_cipher=NULL,last_error=? WHERE id=? AND state IN ('PENDING','RETRY','SENDING') AND attempts>=5 AND (lease_until IS NULL OR lease_until<=?)", "已达到邮件发送次数上限，请检查发送记录", id, clk.iso()) != 0) return;
    if (!mail.ready()) return; // Leave queued mail intact until the administrator configures SMTP.
    String now = clk.iso();
    if (db.update("UPDATE notification_delivery SET state='SENDING',lease_until=?,attempts=attempts+1 WHERE id=? AND attempts<5 AND state IN ('PENDING','RETRY','SENDING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?)", Clk.iso(clk.now() + U.MIN), id, now, now) == 0) return;
    var d = db.row("SELECT * FROM notification_delivery WHERE id=?", id);
    if (!allowed(d)) { db.update("UPDATE notification_delivery SET state='SKIPPED',lease_until=NULL,body_cipher=NULL,last_error=? WHERE id=?", "收件邮箱、订阅或访问权限已变化", id); return; }
    try {
      String body = d.get("body_cipher") == null ? Db.str(d.get("message")) : credentials.box().decrypt(Db.str(d.get("body_cipher")));
      mail.send(Db.str(d.get("email")), Db.str(d.get("subject")), body);
      db.update("UPDATE notification_delivery SET state='SENT',sent_at=?,lease_until=NULL,body_cipher=NULL,last_error=NULL WHERE id=?", clk.iso(), id);
    } catch (Exception e) {
      int attempts = Db.i(d.get("attempts")); boolean failed = attempts >= 5;
      long delay = Math.min(60, 1L << Math.min(attempts - 1, 6)) * U.MIN;
      db.update("UPDATE notification_delivery SET state=?,next_attempt_at=?,lease_until=NULL,last_error=?,body_cipher=CASE WHEN ? THEN NULL ELSE body_cipher END WHERE id=?", failed ? "FAILED" : "RETRY", Clk.iso(clk.now() + delay), "邮件发送失败，请检查发信服务（" + e.getClass().getSimpleName() + "）", failed, id);
    }
  }
}
