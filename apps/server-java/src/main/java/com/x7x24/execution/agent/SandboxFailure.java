package com.x7x24.execution.agent;

/** 把沙盒失败翻译成时间线上看得懂的大白话。半成品一律不采用。 */
public final class SandboxFailure {
  private SandboxFailure() { }
  public static final String CODE = "sandbox_failed";
  /** 可重试失败的自动重试间隔（分钟）：共 2 次。 */
  public static final double[] AUTO_RETRIES = {1, 5};
  /** 只有还没开始花模型钱的网络类失败值得自动重试：拉代码失败、连不上沙盒机器。 */
  public static boolean retryable(AgentJobService.JobFailed f) {
    String raw = f.getMessage() == null ? "" : f.getMessage();
    return !"UNCONFIGURED".equals(f.state) && !"TIMED_OUT".equals(f.state) && (raw.contains("clone 失败") || raw.contains("runner 启动失败"));
  }

  public static String explain(AgentJobService.JobFailed f) {
    String raw = f.getMessage() == null ? "" : f.getMessage();
    String why;
    if ("UNCONFIGURED".equals(f.state)) why = "沙盒还没有配置（AGENT_RUNTIME 需要设为 remote-docker），开发步骤无法运行。可以在项目或本任务里改选「快速方式」。";
    else if (raw.contains("clone 失败")) why = "沙盒机器从 GitHub 拉代码失败（网络不通或超时，已自动重试），这次没有改动任何代码。";
    else if (raw.contains("error_max_turns") || raw.contains("轮数上限")) why = "沙盒里的开发 agent 用完了对话轮数上限还没做完，这次的半成品没有采用。";
    else if ("TIMED_OUT".equals(f.state)) why = "沙盒开发超过了时间上限被停止，这次的半成品没有采用。";
    else if (raw.contains("runner 启动失败")) why = "连不上沙盒机器，这次没有开始开发。";
    else if (raw.contains("退出码")) why = "沙盒里的开发 agent 异常退出，这次的结果没有采用。";
    else if (raw.contains("没有") && raw.contains("改动")) why = "沙盒里的开发 agent 没有改任何文件。";
    else why = "沙盒开发失败，这次的结果没有采用。";
    String detail = raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
    return why + (detail.isBlank() ? "" : "（技术细节：" + detail + "）") + "可以点「再试一次（沙盒）」，或「本任务改用快速方式重试」。";
  }
}
