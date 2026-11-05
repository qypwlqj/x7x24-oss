package com.x7x24.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class U {
  public static final long MIN = 60_000L, HOUR = 3_600_000L, DAY = 24 * HOUR;
  private U() {}
  public static String sha256(String s) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
  }
  public static String uuid() { return UUID.randomUUID().toString(); }
  public static double round(double x, int d) { double p = Math.pow(10, d); return Math.round(x * p) / p; }
  public static double clamp(double x, double a, double b) { return Math.min(b, Math.max(a, x)); }
  public static String ellipsis(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }
  /** 标题指纹：去空白小写后取 sha256 前 24 位 */
  public static String fingerprint(String title) { return sha256(title.replaceAll("\\s+", "").toLowerCase()).substring(0, 24); }
  public static double similarity(String a, String b) {
    Set<String> A = bigrams(a), B = bigrams(b); if (A.isEmpty() || B.isEmpty()) return 0;
    int inter = 0; for (String g : A) if (B.contains(g)) inter++;
    return (double) inter / (A.size() + B.size() - inter);
  }
  private static Set<String> bigrams(String s) { String x = s.replaceAll("\\s+", ""); Set<String> o = new HashSet<>(); for (int i = 0; i < x.length() - 1; i++) o.add(x.substring(i, i + 2)); return o; }
  public static String slug(String s) {
    String x = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return x.isEmpty() ? "task" : x.length() > 40 ? x.substring(0, 40).replaceAll("-+$", "") : x;
  }
}
