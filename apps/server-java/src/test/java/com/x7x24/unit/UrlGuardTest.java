package com.x7x24.unit;

import com.x7x24.llm.UrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class UrlGuardTest {
  @AfterEach void reset() { UrlGuard.resolver = InetAddress::getAllByName; }
  void resolveTo(String ip) { UrlGuard.resolver = h -> new InetAddress[]{InetAddress.getByName(ip)}; }

  @Test void allowsPublicHttps() { resolveTo("104.18.1.1"); assertDoesNotThrow(() -> UrlGuard.check("https://api.deepseek.com", false)); }
  @Test void requiresHttpsExceptDevLocalhost() {
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://api.deepseek.com", false));
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("http://localhost:8080/v1", false));
    assertDoesNotThrow(() -> UrlGuard.check("http://localhost:8080/v1", true));
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("ftp://x.com", false));
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("https://user:pw@x.com", false));
  }
  @Test void blocksPrivateAndMetadataTargets() {
    for (String ip : new String[]{"169.254.169.254", "10.0.0.5", "172.16.3.4", "192.168.1.1", "127.0.0.1", "100.64.0.1", "0.0.0.0", "fd00::1", "::ffff:10.0.0.1", "fe80::1"}) {
      resolveTo(ip); assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("https://evil.example.com", false), ip);
    }
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("https://metadata.google.internal/computeMetadata", false));
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("https://169.254.169.254/latest", false));
    assertThrows(IllegalArgumentException.class, () -> UrlGuard.check("https://localhost/v1", false));
  }
}
