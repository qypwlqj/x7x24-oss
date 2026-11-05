package com.x7x24.integration;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加解密。主密钥来源：环境变量 X7_SECRET_KEY（任意长度口令，SHA-256 派生）；
 * 未设置时在 DATA_DIR 下生成随机密钥文件（权限 600）。密文格式：v1.<base64(iv)>.<base64(ct+tag)>。
 */
public class CryptoBox {
  private final SecretKeySpec key; private final String origin;
  private static final SecureRandom RNG = new SecureRandom();

  public CryptoBox(String envSecret, Path dataDir) {
    try {
      if (envSecret != null && !envSecret.isBlank()) { key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(envSecret.getBytes("UTF-8")), "AES"); origin = "env"; }
      else {
        Files.createDirectories(dataDir); Path f = dataDir.resolve("secret.key"); byte[] raw;
        if (Files.exists(f)) raw = Base64.getDecoder().decode(Files.readString(f).trim());
        else {
          raw = new byte[32]; RNG.nextBytes(raw);
          try { Files.createFile(f, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
          catch (UnsupportedOperationException e) { Files.createFile(f); }
          Files.writeString(f, Base64.getEncoder().encodeToString(raw));
        }
        try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignore) { /* 非 POSIX 文件系统 */ }
        key = new SecretKeySpec(raw, "AES"); origin = "file";
      }
    } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException("无法初始化加密主密钥：" + e.getClass().getSimpleName()); }
  }
  /** 主密钥来源（env / file），仅用于诊断，不含密钥本身 */
  public String origin() { return origin; }

  /** HMAC-SHA256（用于会话令牌签名），与加密共用主密钥 */
  public byte[] mac(String data) {
    try { var m = javax.crypto.Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256")); return m.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    catch (Exception e) { throw new IllegalStateException("签名失败"); }
  }

  public String encrypt(String plain) {
    try {
      byte[] iv = new byte[12]; RNG.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
      return "v1." + Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(ct);
    } catch (Exception e) { throw new IllegalStateException("加密失败"); }
  }
  public String decrypt(String blob) {
    try {
      String[] p = blob.split("\\.");
      if (p.length != 3 || !p[0].equals("v1")) throw new IllegalArgumentException("密文格式不正确");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(p[1])));
      return new String(c.doFinal(Base64.getDecoder().decode(p[2])), "UTF-8");
    } catch (Exception e) { throw new IllegalStateException("解密失败：主密钥可能已变更，请重新连接 GitHub"); }
  }
}
