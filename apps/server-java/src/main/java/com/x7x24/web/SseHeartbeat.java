package com.x7x24.web;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** 不注册为 TaskScheduler，避免改变业务 @Scheduled 的默认调度器。 */
@Component
public final class SseHeartbeat {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SseHeartbeat.class);
  private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, r -> {
    var t = new Thread(r, "sse-heartbeat"); t.setDaemon(true); return t;
  });
  private final long intervalMs;
  public SseHeartbeat(@Value("${x7x24.sse.heartbeat-ms:15000}") long intervalMs) {
    this.intervalMs = Math.max(100, intervalMs); scheduler.setRemoveOnCancelPolicy(true);
  }
  public long intervalMs() { return intervalMs; }
  public ScheduledFuture<?> schedule(Runnable beat) {
    return scheduler.scheduleWithFixedDelay(() -> {
      try { beat.run(); } catch (RuntimeException e) { log.warn("sse_heartbeat_failed type={}", e.getClass().getSimpleName()); }
    }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }
  @PreDestroy public void close() { scheduler.shutdownNow(); }
}
