package com.x7x24.engine;

import java.util.Set;

/** A repository release is an artifact, never proof of deployment to a target platform. */
public final class ReleasePolicy {
  private ReleasePolicy() {}
  public static final String GITHUB = "github_release", WECHAT = "wechat_miniprogram";
  public static final String WAIT_CODE = "release_configuration_required";
  public static final Set<String> TARGETS = Set.of(GITHUB, WECHAT);
  public record Capability(String target, boolean ready, String code, String detail) {}
  public record Delivery(String target, String githubRelease, String deployment, String detail) {}

  public static Capability check(ProjectCfg config) {
    String target = config.releaseTarget;
    if (GITHUB.equals(target)) return new Capability(target, true, null, "仅创建并校验 GitHub Release，不代表应用已上线。");
    if (WECHAT.equals(target)) {
      String detail = config.deployWorkflow == null || config.deployWorkflow.isBlank()
          ? "待配置微信小程序发布：尚未接入上传、提审、审核结果及上线校验能力。"
          : "待配置微信小程序发布：当前版本尚不支持执行及核验微信发布；仅填写工作流名称不能证明小程序已上线。";
      return new Capability(target, false, WAIT_CODE, detail + " 请在项目配置中核对发布目标；接入能力后恢复原节点。");
    }
    return new Capability(target == null ? "" : target, false, WAIT_CODE, "发布目标未配置或不受支持，请在项目配置中选择发布目标。");
  }

  public static Delivery describe(Task task, ProjectCfg config) {
    var capability = check(config);
    String artifact = task.context.rel == null ? "NOT_CREATED" : task.context.rel.verified ? "VERIFIED" : "CREATED";
    // No platform adapter currently supplies verifiable deployment evidence. Never infer LIVE from RELEASED or CI.
    return new Delivery(capability.target(), artifact, capability.ready() ? "NOT_REQUESTED" : "WAITING_CONFIGURATION", capability.detail());
  }
}
