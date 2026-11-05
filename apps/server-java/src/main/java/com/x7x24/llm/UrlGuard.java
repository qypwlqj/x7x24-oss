package com.x7x24.llm;

import java.net.*;
import java.util.*;

/**
 * 模型供应商地址校验（防 SSRF）：只允许 https；http 只在开发开关打开时允许 localhost。
 * 解析出的所有 IP 都不能是内网 / 回环 / 链路本地（含云厂商元数据 169.254.169.254）/ 组播 / CGNAT / IPv6 ULA。
 */
public final class UrlGuard {
  private UrlGuard() {}
  static final Set<String> BLOCKED_HOSTS = Set.of("metadata.google.internal", "metadata", "metadata.azure.com", "instance-data", "instance-data.ec2.internal");
  /** 测试可替换的 DNS 解析 */
  public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
  public static Resolver resolver = InetAddress::getAllByName;

  public static URI check(String baseUrl, boolean allowLocalHttp) {
    if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("请填写 Base URL");
    URI u; try { u = new URI(baseUrl.trim()); } catch (URISyntaxException e) { throw new IllegalArgumentException("Base URL 格式不正确"); }
    String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT); String host = u.getHost();
    if (host == null || host.isBlank()) throw new IllegalArgumentException("Base URL 缺少主机名");
    if (u.getUserInfo() != null) throw new IllegalArgumentException("Base URL 不能包含用户名或密码");
    host = host.toLowerCase(Locale.ROOT); if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    boolean localName = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    if (scheme.equals("http")) { if (!(allowLocalHttp && localName)) throw new IllegalArgumentException("Base URL 必须使用 https"); return u; }
    if (!scheme.equals("https")) throw new IllegalArgumentException("Base URL 必须使用 https");
    if (localName && allowLocalHttp) return u;
    if (BLOCKED_HOSTS.contains(host) || host.endsWith(".internal") || host.endsWith(".local") || host.endsWith(".localhost") || localName) throw new IllegalArgumentException("不允许访问内网或元数据地址：" + host);
    InetAddress[] addrs; try { addrs = resolver.resolve(host); } catch (UnknownHostException e) { throw new IllegalArgumentException("无法解析主机名：" + host); }
    for (InetAddress a : addrs) if (blocked(a)) throw new IllegalArgumentException("不允许访问内网或元数据地址：" + host + " → " + a.getHostAddress());
    return u;
  }
  public static boolean blocked(InetAddress a) {
    if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) return true;
    byte[] b = a.getAddress();
    if (b.length == 4) { int x = b[0] & 0xff, y = b[1] & 0xff; return x == 0 || x == 10 || x == 127 || (x == 100 && y >= 64 && y <= 127) || (x == 169 && y == 254) || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168) || (x == 198 && (y == 18 || y == 19)) || x >= 224; }
    if (b.length == 16) {
      if ((b[0] & 0xfe) == 0xfc) return true; // fc00::/7 ULA
      boolean mapped = true; for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
      if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) { try { return blocked(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16))); } catch (UnknownHostException e) { return true; } }
    }
    return false;
  }
}
