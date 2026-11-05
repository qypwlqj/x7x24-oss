package com.x7x24.util;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/** 时钟：生产环境就是系统时间；advance() 只用于测试（把时间向前拨）。 */
@Component
public class Clk {
  private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private final AtomicLong offset = new AtomicLong();
  public long now() { return System.currentTimeMillis() + offset.get(); }
  public String iso() { return iso(now()); }
  public static String iso(long ms) { return F.format(Instant.ofEpochMilli(ms)); }
  public static long parse(String iso) { return Instant.parse(iso).toEpochMilli(); }
  public String dayKey() { return dayKey(now()); }
  public String dayKey(long ms) { return iso(ms).substring(0, 10); }
  public String monthKey() { return iso(now()).substring(0, 7); }
  public String after(double minutes) { return iso(now() + (long) (minutes * U.MIN)); }
  /** 仅测试：向前拨动时钟 */
  public void advance(long ms) { if (ms < 0) throw new IllegalArgumentException("时钟只能往后调"); offset.addAndGet(ms); }
}
