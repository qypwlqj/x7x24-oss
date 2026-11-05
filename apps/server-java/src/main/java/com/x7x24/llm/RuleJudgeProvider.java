package com.x7x24.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无 LLM 凭据时的评审员：确定性规则打分（不是随机/假数据——分数完全由被评审产出物的特征计算得出）。
 * 三位评审员的偏置不同（模拟「不同模型」），以便评审委员会的分歧/仲裁逻辑真实生效。
 */
public class RuleJudgeProvider implements LlmProvider {
  private final String model; private final double bias;
  public RuleJudgeProvider(String model, double bias) { this.model = model; this.bias = bias; }
  @Override public String model() { return model; }

  @Override public Response chat(Request r) {
    JsonNode a = Json.lenient(r.prompt().replaceFirst("^[^{]*", "")); if (a == null) a = Json.M.createObjectNode();
    Map<String, Object> out = new LinkedHashMap<>();
    String role = r.role();
    if (r.system() != null && r.system().contains("工作流评审节点")) {
      // 评审类工作流节点（如安全审计）的规则版：扫描变更内容里的明文密钥 / 私钥 / 硬编码口令，以及治理文件改动
      StringBuilder hits = new StringBuilder();
      a.path("changedFileContents").fields().forEachRemaining(e -> { if (SECRET.matcher(e.getValue().asText("")).find()) hits.append(e.getKey()).append(' '); });
      boolean block = hits.length() > 0;
      out.put("score", block ? 0.1 : Math.round(Math.min(1, 0.85 + bias * 0.1) * 1000) / 1000.0); out.put("block", block);
      out.put("rationale", block ? "规则评审：发现疑似明文密钥或口令：" + hits.toString().trim() : "规则评审：未发现明文密钥、私钥或硬编码口令");
    } else if (role.startsWith("prd.")) {
      int crit = a.path("acceptanceCriteria").size(); String tpl = a.path("template").asText("generic"); String risk = a.path("risk").asText("low");
      boolean known = !tpl.equals("generic");
      double value = 3 + (a.path("problem").asText("").length() > 30 ? 1 : 0) + (known ? 1 : 0);
      double clarity = Math.min(5, 2 + crit + (a.path("examples").size() > 0 ? 1 : 0));
      double feas = tpl.equals("util-function") || tpl.equals("readme-update") ? 5 : tpl.equals("doc") ? 4 : 2;
      double rk = switch (risk) { case "low" -> 5; case "mid" -> 4; case "high" -> 2; default -> 1; };
      Map<String, Double> s = new LinkedHashMap<>();
      s.put("value", c(value + bias * 5)); s.put("clarity", c(clarity + bias * 5)); s.put("feasibility", c(feas + bias * 5)); s.put("risk", c(rk + bias * 5));
      out.put("scores", s); out.put("block", "forbidden".equals(risk));
      out.put("rationale", "模板=" + tpl + "，验收条件 " + crit + " 条，风险 " + risk + "；规则评分卡打分");
      if (feas <= 2) out.put("tag", "infeasible");
    } else if (role.startsWith("accept.")) {
      double score; String why; boolean block = false;
      JsonNode l1 = a.path("l1");
      switch (role) {
        case "accept.cases" -> { int total = l1.path("total").asInt(0), failed = l1.path("failed").asInt(0); score = total == 0 ? 0 : 1.0 - (double) failed / total; why = "验收用例 " + (total - failed) + "/" + total + " 通过"; }
        case "accept.ux" -> { boolean tests = a.path("pr").path("hasTests").asBoolean(false), docs = a.path("pr").path("hasDocs").asBoolean(false); score = 0.7 + (tests ? 0.2 : 0) + (docs ? 0.08 : 0); why = "测试" + (tests ? "有" : "无") + "，文档" + (docs ? "有" : "无"); }
        default -> { int lines = a.path("pr").path("lines").asInt(0); boolean gov = a.path("pr").path("governanceHits").size() > 0; score = gov ? 0 : Math.max(0.5, 1.0 - lines / 2000.0); block = gov; why = "改动 " + lines + " 行" + (gov ? "，触及治理文件" : ""); }
      }
      out.put("score", Math.round(Math.max(0, Math.min(1, score + bias * 0.2)) * 1000) / 1000.0); out.put("block", block); out.put("rationale", why);
    } else {
      out.put("score", 0.8 + bias); out.put("rationale", "规则评审：设计元素齐全");
    }
    String text = Json.str(out);
    return new Response(text, model, r.promptVersion() == null ? "rule-v1" : r.promptVersion(), 0, 0);
  }
  public static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile("(?i)(sk-[a-z0-9]{20,}|ghp_[a-z0-9]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY|(password|passwd|secret|api[_-]?key)\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"'])");
  private static double c(double x) { return Math.round(Math.max(1, Math.min(5, x)) * 10) / 10.0; }
}
