package com.x7x24.llm;

import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.integration.CredentialProvider;
import com.x7x24.util.Clk;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 模型供应商 API Key 的加密存储（secretRef = db:<id>）。只写：对外只给末 4 位与更新时间。 */
@Component
public class ProviderKeys {
  public record Meta(String id, String last4, String updatedAt) {}
  private final Db db; private final Clk clk; private final CredentialProvider creds;
  private final Map<String, String> cache = new ConcurrentHashMap<>();
  public ProviderKeys(Db db, Clk clk, CredentialProvider creds) { this.db = db; this.clk = clk; this.creds = creds; }

  public static boolean validId(String id) { return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,60}"); }
  /** id = <范围>/<供应商 id>，范围为工作区 id 或 _platform */
  public static boolean validFullId(String id) { return id != null && (validId(id) || id.matches("[a-z0-9_]{1,40}/[a-z0-9][a-z0-9_-]{0,60}")); }
  public synchronized void put(String id, String key) {
    if (!validFullId(id)) throw new IllegalArgumentException("供应商 id 不合法");
    String k = key == null ? "" : key.trim(); if (k.length() < 8 || k.length() > 500 || k.matches(".*\\s.*")) throw new IllegalArgumentException("API Key 格式不正确");
    Redactor.register(k);
    String now = clk.iso(), enc = creds.box().encrypt(k), last4 = k.substring(k.length() - 4);
    if (db.update("UPDATE llm_provider_key SET ciphertext=?, last4=?, updated_at=? WHERE id=?", enc, last4, now, id) == 0)
      db.update("INSERT INTO llm_provider_key(id,ciphertext,last4,created_at,updated_at) VALUES(?,?,?,?,?)", id, enc, last4, now, now);
    cache.put(id, k);
  }
  /** 调用时解析；不存在返回空字符串 */
  public String get(String id) {
    var c = cache.get(id); if (c != null) return c;
    var r = db.row("SELECT ciphertext FROM llm_provider_key WHERE id=?", id); if (r == null) return "";
    String k; try { k = creds.box().decrypt((String) r.get("ciphertext")); } catch (Exception e) { return ""; }
    Redactor.register(k); cache.put(id, k); return k;
  }
  public Meta meta(String id) { var r = db.row("SELECT last4, updated_at FROM llm_provider_key WHERE id=?", id); return r == null ? null : new Meta(id, (String) r.get("last4"), (String) r.get("updated_at")); }
  public synchronized void delete(String id) { db.update("DELETE FROM llm_provider_key WHERE id=?", id); cache.remove(id); }
  /** 启动时把已存密钥登记到 Redactor，防止出现在日志 / 错误里 */
  public void registerAll() { for (var r : db.rows("SELECT id FROM llm_provider_key")) get((String) r.get("id")); }
}
