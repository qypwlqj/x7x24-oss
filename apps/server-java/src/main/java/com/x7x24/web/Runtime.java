package com.x7x24.web;

import com.x7x24.adapters.DeliveryTools;
import com.x7x24.engine.*;
import com.x7x24.gov.KillSwitch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时调度：tick（引擎）+ intake（从 GitHub Issues 抓取信号）。Temporal 替换点见 WorkflowOrchestrator。 */
@Component
public class Runtime {
  private static final Logger log = LoggerFactory.getLogger(Runtime.class);
  private final Engine engine; private final Intake intake; private final X7Props props; private final Discovery discovery;
  private final java.util.concurrent.ExecutorService scans = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "project-discovery"); t.setDaemon(true); return t; });
  private java.util.concurrent.Future<?> scanRun;
  public Runtime(Engine engine, Intake intake, X7Props props, Discovery discovery) { this.engine = engine; this.intake = intake; this.props = props; this.discovery = discovery; }

  private final java.util.concurrent.ScheduledExecutorService watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "execution-watchdog"); t.setDaemon(true); return t; });
  @jakarta.annotation.PostConstruct public void startWatchdog() {
    watchdog.scheduleWithFixedDelay(() -> { if (props.engine.enabled) try { engine.watchdog(); } catch (Exception e) { log.error("watchdog failed", e); } }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
  }
  @Scheduled(fixedDelayString = "${x7x24.engine.tick-ms:2000}", initialDelay = 3000)
  public void tick() { if (!props.engine.enabled) return; try { engine.tick(); } catch (Exception e) { log.error("tick failed", e); } }

  @Scheduled(fixedDelayString = "${x7x24.engine.intake-ms:30000}", initialDelay = 4000)
  public void intake() { if (!props.engine.enabled) return; try { intake.poll(); } catch (Exception e) { log.warn("intake failed: {}", com.x7x24.adapters.Redactor.redact(e)); } }
  @Scheduled(fixedDelay = 60000, initialDelay = 10000)
  public void discovery() { if (!props.engine.enabled || scanRun != null && !scanRun.isDone()) return; scanRun = scans.submit(discovery::poll); }
  @jakarta.annotation.PreDestroy public void close() { watchdog.shutdownNow(); scans.shutdownNow(); }
}
