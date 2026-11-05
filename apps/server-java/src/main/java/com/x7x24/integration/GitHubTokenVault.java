package com.x7x24.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.x7x24.adapters.GitHubClient.GitHubException;
import com.x7x24.adapters.Redactor;
import com.x7x24.db.Db;
import com.x7x24.engine.X7Props;
import com.x7x24.util.Clk;
import com.x7x24.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-only token rotation. Refreshes serialize on the persisted row, across JVMs. */
final class GitHubTokenVault {
  private final Db db; private final CryptoBox box; private final X7Props props; private final Clk clk; private final String clientSecret;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  private record InstallationToken(String value, long expiresAt) {}
  private final Map<String, InstallationToken> installationTokens = new ConcurrentHashMap<>();

  GitHubTokenVault(Db db, CryptoBox box, X7Props props, Clk clk, String clientSecret) {
    this.db = db; this.box = box; this.props = props; this.clk = clk; this.clientSecret = clientSecret;
  }
  static String expiry(JsonNode response, String seconds, long now) { return response.has(seconds) ? Clk.iso(now + response.path(seconds).asLong() * 1000L) : null; }
  static long timestamp(Object value) {
    if (value == null) return Long.MAX_VALUE;
    try { return Clk.parse(value.toString().replace(" UTC", "Z").replace(' ', 'T')); } catch (RuntimeException e) { return Long.MAX_VALUE; }
  }
  String encrypted(JsonNode response, String field) { String value = response.path(field).asText(""); Redactor.register(value); return value.isBlank() ? null : box.encrypt(value); }

  void saveUser(String userId, JsonNode grant) {
    String access = grant.path("access_token").asText("");
    if (access.isBlank()) throw new GitHubException(401, "GitHub 授权需要更新", "/user");
    Redactor.register(access);
    db.tx(() -> {
      db.row("SELECT id FROM app_user WHERE id=? FOR UPDATE", userId);
      if (db.count("SELECT COUNT(*) FROM github_user_authorization WHERE user_id=?", userId) == 0)
        db.update("INSERT INTO github_user_authorization(user_id,ciphertext,updated_at) VALUES(?,?,?)", userId, box.encrypt(access), clk.iso());
      db.update("UPDATE github_user_authorization SET ciphertext=?,refresh_ciphertext=?,expires_at=?,refresh_expires_at=?,updated_at=? WHERE user_id=?", box.encrypt(access), encrypted(grant, "refresh_token"), expiry(grant, "expires_in", clk.now()), expiry(grant, "refresh_token_expires_in", clk.now()), clk.iso(), userId);
      db.update("UPDATE integration_credentials SET expires_at=?,connection_state=CASE WHEN connection_state IN ('repositories_required','approval_pending','permissions_required') THEN connection_state ELSE 'ready' END,checked_at=? WHERE user_id=?", expiry(grant, "expires_in", clk.now()), clk.iso(), userId);
    });
  }

  String user(String userId, boolean force) { return rotate("github_user_authorization", "user_id", userId, force); }
  String workspace(String id, boolean force) { return rotate("integration_credentials", "id", id, force); }
  private String rotate(String table, String key, String id, boolean force) {
    // Only the two fixed table/key pairs above reach this method.
    return db.tx(() -> {
      var row = db.row("SELECT * FROM " + table + " WHERE " + key + "=? FOR UPDATE", id);
      if (row == null) return "";
      String cipher = Db.str(row.get("ciphertext"));
      String refreshCipher = Db.str(row.get("refresh_ciphertext"));
      boolean expiring = timestamp(row.get("expires_at")) <= clk.now() + 60_000;
      if (!force && !expiring) return decrypt(cipher);
      if (refreshCipher == null || timestamp(row.get("refresh_expires_at")) <= clk.now()) {
        if (!expiring && !force) return decrypt(cipher);
        throw new GitHubException(401, "GitHub 授权需要更新，请重新授权", "/user");
      }
      Map<String, String> form = new LinkedHashMap<>(); form.put("client_id", props.github.oauthClientId); form.put("grant_type", "refresh_token"); form.put("refresh_token", decrypt(refreshCipher));
      if (clientSecret != null && !clientSecret.isBlank()) form.put("client_secret", clientSecret);
      String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
      JsonNode grant;
      try { grant = send(props.github.webUrl + "/login/oauth/access_token", null, "application/x-www-form-urlencoded", body); }
      catch (GitHubException e) { if (e.status == 400 || e.status == 401 || e.status == 403) throw new GitHubException(401, "GitHub 授权需要更新，请重新授权", "/user"); throw e; }
      if (grant.has("error") || grant.path("access_token").asText("").isBlank())
        throw new GitHubException(401, "GitHub 授权需要更新，请重新授权", "/user");
      String access = grant.path("access_token").asText(); Redactor.register(access);
      // Both tokens rotate together. Never keep the consumed refresh token.
      db.update("UPDATE " + table + " SET ciphertext=?,refresh_ciphertext=?,expires_at=?,refresh_expires_at=? WHERE " + key + "=?", box.encrypt(access), encrypted(grant, "refresh_token"), expiry(grant, "expires_in", clk.now()), expiry(grant, "refresh_token_expires_in", clk.now()), id);
      if (table.equals("github_user_authorization")) {
        db.update("UPDATE github_user_authorization SET updated_at=? WHERE user_id=?", clk.iso(), id);
        db.update("UPDATE integration_credentials SET expires_at=?,connection_state=CASE WHEN connection_state IN ('repositories_required','approval_pending','permissions_required') THEN connection_state ELSE 'ready' END,checked_at=? WHERE user_id=?", expiry(grant, "expires_in", clk.now()), clk.iso(), id);
      }
      return access;
    });
  }
  private String decrypt(String cipher) { String value = cipher == null || cipher.isBlank() ? "" : box.decrypt(cipher); Redactor.register(value); return value; }
  boolean installationEnabled() { return props.github.privateKey != null && !props.github.privateKey.isBlank() && (!props.github.appId.isBlank() || !props.github.oauthClientId.isBlank()); }
  void invalidateInstallation(String key) { installationTokens.remove(key); }
  synchronized String installation(String cacheKey, long installationId, long repositoryId) {
    var cached = installationTokens.get(cacheKey);
    if (cached != null && cached.expiresAt > clk.now() + 60_000) return cached.value;
    var grant = send(props.github.apiUrl + "/app/installations/" + installationId + "/access_tokens", jwt(), "application/json", Json.str(Map.of("repository_ids", List.of(repositoryId))));
    String token = grant.path("token").asText("");
    if (token.isBlank()) throw new GitHubException(403, "仓库授权需要更新", "/app/installations/" + installationId);
    Redactor.register(token);
    long expiresAt = timestamp(grant.path("expires_at").asText());
    if (expiresAt == Long.MAX_VALUE || expiresAt <= clk.now()) throw new GitHubException(502, "GitHub 没有返回有效的授权期限，请重试", "/app/installations/" + installationId);
    installationTokens.put(cacheKey, new InstallationToken(token, expiresAt));
    return token;
  }
  private String jwt() {
    try {
      String pem = props.github.privateKey.replace("\\n", "\n"); boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
      byte[] key = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
      if (pkcs1) {
        byte[] algorithm = java.util.HexFormat.of().parseHex("020100300d06092a864886f70d0101010500");
        byte[] octet = der(4, key); byte[] content = Arrays.copyOf(algorithm, algorithm.length + octet.length); System.arraycopy(octet, 0, content, algorithm.length, octet.length); key = der(48, content);
      }
      var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));
      var b64 = Base64.getUrlEncoder().withoutPadding();
      String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      long seconds = clk.now() / 1000;
      String payload = b64.encodeToString(Json.str(Map.of("iat", seconds - 60, "exp", seconds + 540, "iss", props.github.oauthClientId.isBlank() ? props.github.appId : props.github.oauthClientId)).getBytes(StandardCharsets.UTF_8));
      String unsigned = header + "." + payload; Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(privateKey); signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
      String token = unsigned + "." + b64.encodeToString(signature.sign()); Redactor.register(token); return token;
    } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("GitHub App 私钥配置无效，请联系管理员检查"); }
  }
  private static byte[] der(int tag, byte[] bytes) {
    int size = bytes.length; byte[] length = size < 128 ? new byte[]{(byte) size} : size < 256 ? new byte[]{(byte) 0x81, (byte) size} : new byte[]{(byte) 0x82, (byte) (size >> 8), (byte) size};
    byte[] result = new byte[1 + length.length + size]; result[0] = (byte) tag; System.arraycopy(length, 0, result, 1, length.length); System.arraycopy(bytes, 0, result, 1 + length.length, size); return result;
  }
  private JsonNode send(String url, String bearer, String contentType, String body) {
    try {
      var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("User-Agent", "7x24-agent").header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
      if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) throw new GitHubException(response.statusCode(), "GitHub 授权更新失败", URI.create(url).getPath());
      return Json.tree(response.body());
    } catch (GitHubException e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new GitHubException(0, "暂时无法连接 GitHub，请重试", "/authorization"); }
    catch (Exception e) { throw new GitHubException(0, "暂时无法连接 GitHub，请重试", "/authorization"); }
  }
  private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
