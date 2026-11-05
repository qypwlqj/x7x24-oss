package com.x7x24.adapters;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 凭据脱敏：任何进入日志 / 审计 / 证据 / 错误信息的字符串都必须先过这里。 */
public final class Redactor {
  private Redactor() {}
  private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
  private static final Pattern[] PATTERNS = {
      Pattern.compile("gh[pousr]_[A-Za-z0-9]{16,}"), Pattern.compile("github_pat_[A-Za-z0-9_]{16,}"),
      Pattern.compile("(?i)(bearer|token)\\s+[A-Za-z0-9._\\-]{12,}"), Pattern.compile("sk-[A-Za-z0-9]{16,}") };
  public static void register(String secret) { if (secret != null && secret.length() >= 6) SECRETS.add(secret); }
  /** 内容里是否含已注册密钥或典型令牌格式（不含宽松的 bearer/token 词组规则，避免误伤文档）。 */
  public static boolean containsSecret(String s) {
    if (s == null) return false;
    for (String sec : SECRETS) if (s.contains(sec)) return true;
    return PATTERNS[0].matcher(s).find() || PATTERNS[1].matcher(s).find() || PATTERNS[3].matcher(s).find();
  }
  public static String redact(Object in) {
    String s = in == null ? "" : in instanceof Throwable t ? String.valueOf(t.getMessage()) : in.toString();
    for (String sec : SECRETS) s = s.replace(sec, "***");
    for (Pattern p : PATTERNS) s = p.matcher(s).replaceAll(m -> m.group().matches("(?i)(bearer|token)\\s+.*") ? m.group().split("\\s+")[0] + " ***" : "***");
    return s;
  }
  /** Scrub string leaves before canonical serialization and hashing; preserve JSON types. */
  public static String json(Object in) {
    com.fasterxml.jackson.databind.JsonNode tree = com.x7x24.util.Json.M.valueToTree(in);
    return com.x7x24.util.Json.canonical(scrub(tree));
  }
  private static com.fasterxml.jackson.databind.JsonNode scrub(com.fasterxml.jackson.databind.JsonNode n) {
    if (n.isTextual()) return com.x7x24.util.Json.M.getNodeFactory().textNode(redact(n.asText()));
    if (n.isObject()) {
      var out = com.x7x24.util.Json.M.createObjectNode();
      n.fields().forEachRemaining(e -> out.set(redact(e.getKey()), scrub(e.getValue())));
      return out;
    }
    if (n.isArray()) {
      var out = com.x7x24.util.Json.M.createArrayNode(); n.forEach(e -> out.add(scrub(e))); return out;
    }
    return n;
  }
}
