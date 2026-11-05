package com.x7x24.chat;

import java.util.*;
import java.util.regex.Pattern;

/**
 * B 类操作的引导（docs/design/chat-workspace-redesign.md 第 6 节，D6）。
 * 模型只能给出主题 topic；跳转页面由这里的固定表决定，不信任模型给的链接。
 */
public final class Guides {
  private Guides() {}
  public record Guide(String topic, String title, String reason, String page, String href) {
    public Map<String, Object> toMap() { return Map.of("topic", topic, "title", title, "reason", reason, "page", page, "href", href); }
  }
  public static final Map<String, Guide> ALL = new LinkedHashMap<>();
  static void put(String t, String title, String reason, String page, String href) { ALL.put(t, new Guide(t, title, reason, page, href)); }
  static {
    put("delete_project", "删除或归档项目", "不可逆", "产品线与项目", "/catalog");
    put("delete_line", "删除或归档产品线", "不可逆", "产品线与项目", "/catalog");
    put("member_role", "改角色、移出成员或转让所有者", "涉及权限", "工作区成员", "/workspace");
    put("budget", "调整预算或配额", "涉及费用", "预算", "/budget");
    put("approval", "合并或发布审批", "需要你亲自把关", "任务", "/tasks");
    put("secrets", "模型、密钥配置", "涉及密钥", "模型", "/models");
    put("integrations", "集成和通知配置", "涉及凭据", "集成", "/integrations");
    put("governance", "治理配置（自治等级、风险规则）", "影响所有任务", "治理", "/governance");
  }
  public static Guide of(String topic) { return topic == null ? null : ALL.get(topic.trim()); }

  static final String HEAD = "^(?:请)?(?:帮我)?(?:把|给)?";
  static final List<Map.Entry<Pattern, String>> RULES = List.of(
      Map.entry(Pattern.compile(HEAD + "(?:删除|删掉|归档)\\s*(?:这个|一个)?项目"), "delete_project"),
      Map.entry(Pattern.compile(HEAD + "(?:删除|删掉|归档)\\s*(?:这条|一条)?产品线"), "delete_line"),
      Map.entry(Pattern.compile(HEAD + "(?:转让(?:所有者|所有权)?|移出|踢出)"), "member_role"),
      Map.entry(Pattern.compile(HEAD + "@?[A-Za-z0-9-]{1,39}\\s*(?:改成|设为|提升为|升为|降为)\\s*(?:管理员|所有者|成员|只读)"), "member_role"),
      Map.entry(Pattern.compile(HEAD + "(?:改|调|提高|增加|降低)(?:一下)?\\s*(?:预算|配额)"), "budget"),
      Map.entry(Pattern.compile(HEAD + "(?:批准|审批|合并)\\s*(?:这个)?(?:PR|合并|发布)", Pattern.CASE_INSENSITIVE), "approval"),
      Map.entry(Pattern.compile(HEAD + "(?:改|换|设置|配置)(?:一下)?\\s*(?:模型|密钥|api ?key|令牌)", Pattern.CASE_INSENSITIVE), "secrets"));
  /** 只认明确的命令句开头，普通需求（如「支持删除项目功能」）不会被拦 */
  public static Guide parse(String text) {
    String t = text == null ? "" : text.trim();
    for (var r : RULES) if (r.getKey().matcher(t).find()) return ALL.get(r.getValue());
    return null;
  }
  public static String reply(Guide g) { return "「" + g.title() + "」需要你在页面上亲自完成，我不能代你执行（" + g.reason() + "）。请到「" + g.page() + "」页面操作。"; }
}
