package com.x7x24.engine;

import java.util.*;

public class X7Props {
  public static class Adapter { public String mode = "offline"; public boolean dryRun = true; public int ciPollMs = 5000, ciTimeoutSec = 1200, ciStartTimeoutSec = 45, maxRetries = 3, maxWaitMs = 60000; public String prometheusUrl; }
  public static class Github { public String token = ""; public String apiUrl = "https://api.github.com", webUrl = "https://github.com", oauthClientId = "", appSlug = "", appId = "", privateKey = ""; }
  public static class Llm { public String baseUrl = "", apiKey = "", model = "", cheapModel = "", judgeModels = ""; public double temperature = 0.2, priceInPer1m = 0.30, priceOutPer1m = 1.20; public int timeoutSec = 180, maxRetries = 3, maxConcurrency = 4; public long minIntervalMs = 0; }
  public static class Engine { public boolean enabled = true; public long tickMs = 2000, intakeMs = 30000; public int workers = 4; public double observeHours = -1; public boolean testClock = false; }
  public Adapter adapter = new Adapter(); public Github github = new Github(); public Llm llm = new Llm(); public Engine engine = new Engine();
  public List<LegacyLineCfg> lines = new ArrayList<>();
  public String webDir = "../web/dist";
}
