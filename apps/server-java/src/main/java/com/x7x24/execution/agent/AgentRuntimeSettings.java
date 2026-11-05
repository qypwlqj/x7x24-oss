package com.x7x24.execution.agent;

import org.springframework.core.env.Environment;

/**
 * AGENT_RUNTIME 只表示沙盒是否可用（remote-docker = 可用）。开发方式由项目 / 任务设置主动选择（见 {@link DevMode}），
 * 沙盒失败后不会自动改用快速方式。AGENT_FALLBACK_LEGACY 已废弃，不再读取。
 * remote-docker 需要同时配置 AGENT_RUNNER_URL 与 AGENT_RUNNER_HMAC_KEY。
 */
public record AgentRuntimeSettings(String mode, String runnerUrl, String hmacKey, String model, AgentRuntime.Limits limits, long pollMs,
    boolean selfTest, boolean uiAcceptance, String acceptanceMemory) {
  public AgentRuntimeSettings(String mode, String runnerUrl, String hmacKey, String model, AgentRuntime.Limits limits, long pollMs) {
    this(mode, runnerUrl, hmacKey, model, limits, pollMs, false, false, "2560m");
  }
  /** 页面验收只在 remote-docker 且 UI_ACCEPTANCE=on 时可用。 */
  public boolean uiAcceptanceEnabled() { return remote() && uiAcceptance; }
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimeSettings.class);
  public static final String UNCONFIGURED = "", REMOTE = "remote-docker";
  public boolean remote() { return REMOTE.equals(mode); }
  public AgentRuntime newRemoteRuntime() { return new RemoteDockerRuntime(runnerUrl, hmacKey); }
  public static AgentRuntimeSettings unconfigured() { return new AgentRuntimeSettings(UNCONFIGURED, "", "", "deepseek-flash", AgentRuntime.Limits.defaults(), 2000); }
  public static AgentRuntimeSettings from(Environment e) {
    String mode = e.getProperty("AGENT_RUNTIME", UNCONFIGURED).trim();
    if (mode.equals("legacy-json")) { LOG.warn("AGENT_RUNTIME=legacy-json 已不再表示开发方式，按「沙盒不可用」处理；快速方式请在项目或任务设置里选择"); mode = UNCONFIGURED; }
    if (!mode.equals(UNCONFIGURED) && !mode.equals(REMOTE)) throw new IllegalStateException("AGENT_RUNTIME 只能是 remote-docker（或不设置）");
    if (e.getProperty("AGENT_FALLBACK_LEGACY", Boolean.class, false)) LOG.warn("AGENT_FALLBACK_LEGACY 已废弃：沙盒失败不再自动回退；快速方式请在项目或任务设置里主动选择");
    var d = AgentRuntime.Limits.defaults();
    var limits = new AgentRuntime.Limits(e.getProperty("AGENT_WALL_CLOCK_SEC", Integer.class, d.wallClockSec()), e.getProperty("AGENT_MAX_TURNS", Integer.class, d.maxTurns()),
        e.getProperty("AGENT_MEMORY", d.memory()), e.getProperty("AGENT_CPUS", Double.class, d.cpus()));
    var s = new AgentRuntimeSettings(mode, e.getProperty("AGENT_RUNNER_URL", ""), e.getProperty("AGENT_RUNNER_HMAC_KEY", ""), e.getProperty("AGENT_MODEL", "deepseek-flash"),
        limits, e.getProperty("AGENT_POLL_MS", Long.class, 2000L),
        e.getProperty("AGENT_SELF_TEST_BROWSER", Boolean.class, false), "on".equalsIgnoreCase(e.getProperty("UI_ACCEPTANCE", "off")), e.getProperty("UI_ACCEPTANCE_MEMORY", "2560m"));
    if (s.remote() && (s.runnerUrl.isBlank() || s.hmacKey.length() < 32)) throw new IllegalStateException("remote-docker 需要 AGENT_RUNNER_URL 和至少 32 字符的 AGENT_RUNNER_HMAC_KEY");
    return s;
  }
  @Override public String toString() { return "AgentRuntimeSettings[" + mode + "," + runnerUrl + "]"; }
}
