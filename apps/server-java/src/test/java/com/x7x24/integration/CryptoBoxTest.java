package com.x7x24.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CryptoBoxTest {
  @TempDir Path dir;

  @Test void roundTripWithEnvKeyAndNoPlaintextInCiphertext() {
    var box = new CryptoBox("my-passphrase", dir); String token = "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789"; // fake test key
    String enc = box.encrypt(token);
    assertTrue(enc.startsWith("v1.")); assertFalse(enc.contains(token)); assertNotEquals(enc, box.encrypt(token), "每次加密使用新的随机 IV");
    assertEquals(token, box.decrypt(enc)); assertEquals("env", box.origin());
    assertFalse(Files.exists(dir.resolve("secret.key")), "有环境变量主密钥时不生成密钥文件");
  }
  @Test void wrongKeyOrTamperedCiphertextFails() {
    String enc = new CryptoBox("k1", dir).encrypt("secret");
    assertThrows(IllegalStateException.class, () -> new CryptoBox("k2", dir).decrypt(enc));
    String tampered = enc.substring(0, enc.length() - 4) + (enc.endsWith("AAAA") ? "BBBB" : "AAAA");
    assertThrows(IllegalStateException.class, () -> new CryptoBox("k1", dir).decrypt(tampered));
  }
  @Test void generatesKeyFileWith600PermissionsAndReusesIt() throws Exception {
    Path data = dir.resolve("nested/data");
    var a = new CryptoBox("", data); Path f = data.resolve("secret.key");
    assertTrue(Files.exists(f)); assertEquals("file", a.origin());
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f);
    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    String enc = a.encrypt("hello");
    assertEquals("hello", new CryptoBox(null, data).decrypt(enc), "重启后读取同一密钥文件仍可解密");
  }
}
