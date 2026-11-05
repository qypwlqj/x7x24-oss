package com.x7x24.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** 需求草稿（对话中由 agent 与用户共同填写；确认后生成任务）。结构字段键名固定为英文，正文用用户的语言。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Draft {
  public String presetId;
  public String title, goal, projectId, risk, size, classification;
  public List<String> scope = new ArrayList<>(), outOfScope = new ArrayList<>(), acceptance = new ArrayList<>(), classifiedBy = new ArrayList<>(), targetPaths = new ArrayList<>(), openQuestions = new ArrayList<>();
  /** C2：agent 提出的流程调整（原样保存，确认前由 PlanService 校验） */
  public JsonNode adjustments;

  /** 把 agent / 用户给的局部字段合并进来（null = 不改；数组整体替换） */
  public void merge(JsonNode p) {
    if (p == null || !p.isObject()) return;
    presetId = str(p, "presetId", presetId);
    title = str(p, "title", title); goal = str(p, "goal", goal); projectId = str(p, "projectId", projectId); risk = str(p, "risk", risk);
    size = str(p, "size", size); classification = str(p, "classification", classification);
    scope = list(p, "scope", scope); outOfScope = list(p, "outOfScope", outOfScope); acceptance = list(p, "acceptance", acceptance);
    classifiedBy = list(p, "classifiedBy", classifiedBy); targetPaths = list(p, "targetPaths", targetPaths); openQuestions = list(p, "openQuestions", openQuestions);
    if (p.has("adjustments") && (p.get("adjustments").isArray() || p.get("adjustments").isNull())) adjustments = p.get("adjustments").isNull() ? null : p.get("adjustments");
  }
  private static String str(JsonNode p, String k, String d) {
    if (!p.has(k)) return d; var v = p.get(k); if (v.isNull()) return null;
    String s = v.asText().trim(); return s.length() > 2000 ? s.substring(0, 2000) : s;
  }
  private static List<String> list(JsonNode p, String k, List<String> d) {
    if (!p.has(k) || !p.get(k).isArray()) return d;
    List<String> out = new ArrayList<>(); for (var x : p.get(k)) { String s = x.asText().trim(); if (!s.isEmpty() && out.size() < 30) out.add(s.length() > 1000 ? s.substring(0, 1000) : s); }
    return out;
  }

  /** 生成任务正文（Markdown） */
  public String prdMarkdown() {
    StringBuilder b = new StringBuilder();
    b.append("## 目标\n").append(goal == null ? "" : goal).append("\n\n## 范围\n"); for (var s : scope) b.append("- ").append(s).append('\n');
    b.append("\n## 不包含\n"); for (var s : outOfScope) b.append("- ").append(s).append('\n');
    b.append("\n## 验收标准\n"); int i = 1; for (var s : acceptance) b.append(i++).append(". ").append(s).append('\n');
    if (risk != null || size != null || classification != null) b.append("\n## 评估\n- 风险：").append(n(risk)).append("\n- 规模：").append(n(size)).append("\n- 类型：").append(n(classification)).append('\n');
    b.append("\n_来源：需求对话_\n");
    return b.toString();
  }
  public String acceptanceText() { StringBuilder b = new StringBuilder(); int i = 1; for (var s : acceptance) b.append(i++).append(". ").append(s).append('\n'); return b.toString().trim(); }
  private static String n(String s) { return s == null ? "-" : s; }
}
