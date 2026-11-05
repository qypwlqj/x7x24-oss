package com.x7x24.execution.agent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 与 runnerd 共享的签名格式：HMAC-SHA256(key, METHOD \n PATH?QUERY \n TS \n NONCE \n sha256hex(body))。 */
public final class Hmac {
  private Hmac() {}
  public static String canonical(String method, String pathAndQuery, String ts, String nonce, byte[] body) {
    return method.toUpperCase() + "\n" + pathAndQuery + "\n" + ts + "\n" + nonce + "\n" + sha256(body == null ? new byte[0] : body);
  }
  public static String sign(String key, String canonical) {
    try { var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception e) { throw new IllegalStateException("HMAC 不可用", e); }
  }
  public static String sha256(byte[] b) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
  }
}
