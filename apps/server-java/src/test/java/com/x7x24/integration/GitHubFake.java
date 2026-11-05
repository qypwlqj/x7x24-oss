package com.x7x24.integration;

import okhttp3.mockwebserver.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** 同时扮演 github.com（设备码）与 api.github.com 的假服务。 */
class GitHubFake {
  final MockWebServer server = new MockWebServer();
  final ConcurrentLinkedQueue<RecordedRequest> seen = new ConcurrentLinkedQueue<>();
  final Set<String> validTokens = ConcurrentHashMap.newKeySet();
  final Queue<String> deviceCodeResponses = new ConcurrentLinkedQueue<>(), tokenResponses = new ConcurrentLinkedQueue<>();
  volatile String scopes = "repo, workflow"; volatile boolean hasWorkflowFile = true, workflowDispatch = true, workflowLegacy = false; volatile int protectionStatus = 404; volatile boolean push = true; volatile List<String> installations = List.of("1");
  volatile String login = "octo"; volatile boolean contentsDenied = false; volatile boolean appNoPerms = false;
  volatile int tokenStatus = 200, installationTokenStatus = 201;

  GitHubFake() {
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest r) {
        seen.add(r); String p = r.getPath(), m = r.getMethod();
        MockResponse js = new MockResponse().setHeader("Content-Type", "application/json").setHeader("x-ratelimit-limit", "5000").setHeader("x-ratelimit-remaining", "4999");
        if (p.equals("/login/device/code")) { String b = deviceCodeResponses.poll(); return js.setBody(b != null ? b : "{\"device_code\":\"dc123\",\"user_code\":\"ABCD-1234\",\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}"); }
        if (p.equals("/login/oauth/access_token")) { String b = tokenResponses.poll(); return js.setResponseCode(tokenStatus).setBody(b != null ? b : "{\"error\":\"authorization_pending\"}"); }
        if (p.equals("/app/installations/1/access_tokens")) return js.setResponseCode(installationTokenStatus).setBody("{\"token\":\"ghs_installation_test_token\",\"expires_at\":\"" + java.time.Instant.now().plusSeconds(3600) + "\"}");
        String auth = r.getHeader("Authorization"); String tok = auth == null ? "" : auth.replace("Bearer ", "");
        if (!validTokens.contains(tok)) return js.setResponseCode(401).setBody("{\"message\":\"Bad credentials\"}");
        if (p.equals("/user")) return js.setHeader("x-oauth-scopes", scopes).setBody("{\"login\":\"" + login + "\",\"avatar_url\":\"https://avatars.example/u.png\"}");
        if (p.startsWith("/user/repos")) return js.setBody("[{\"full_name\":\"octo/alpha\",\"private\":true,\"default_branch\":\"main\",\"description\":\"A\",\"pushed_at\":\"2026-10-01T00:00:00Z\",\"permissions\":{\"push\":true}},{\"full_name\":\"octo/beta\",\"private\":false,\"default_branch\":\"dev\",\"permissions\":{\"push\":false}}]");
        if (p.startsWith("/user/installations?")) { StringBuilder b = new StringBuilder("{\"installations\":["); for (int i = 0; i < installations.size(); i++) b.append(i > 0 ? "," : "").append("{\"id\":").append(installations.get(i)).append(appNoPerms ? ",\"permissions\":{}" : ",\"permissions\":{\"metadata\":\"read\",\"contents\":\"write\"}").append("}"); return js.setBody(b.append("]}").toString()); }
        if (p.startsWith("/user/installations/1/repositories") && appNoPerms) return js.setBody("{\"total_count\":0,\"repositories\":[]}");
        if (p.startsWith("/user/installations/1/repositories")) return js.setBody("{\"repositories\":[{\"id\":101,\"full_name\":\"octo/app-repo\",\"private\":true,\"default_branch\":\"main\",\"permissions\":{\"push\":true}}]}");
        if (p.equals("/repos/o/r")) return js.setBody("{\"private\":true,\"default_branch\":\"main\",\"html_url\":\"https://github.com/o/r\",\"has_issues\":true,\"permissions\":{\"push\":" + push + ",\"pull\":true}}");
        if (p.startsWith("/repos/o/r/git/trees/")) return js.setBody("{\"truncated\":false,\"tree\":[{\"path\":\"backend/go.mod\",\"type\":\"blob\"},{\"path\":\"backend/main.go\",\"type\":\"blob\"},{\"path\":\"frontend/package.json\",\"type\":\"blob\"},{\"path\":\"frontend/src/App.tsx\",\"type\":\"blob\"},{\"path\":\".github/workflows/ci.yml\",\"type\":\"blob\"}]}");
        if (p.startsWith("/repos/o/r/contents/backend/go.mod")) return js.setBody("{\"content\":\"" + Base64.getEncoder().encodeToString("module x\ngo 1.23\nrequire github.com/gin-gonic/gin v1\n".getBytes()) + "\"}");
        if (p.startsWith("/repos/o/r/contents/frontend/package.json")) return js.setBody("{\"content\":\"" + Base64.getEncoder().encodeToString("{\"scripts\":{\"test\":\"vitest run\"},\"dependencies\":{\"react\":\"18\"},\"devDependencies\":{\"typescript\":\"5\"}}".getBytes()) + "\"}");
        if (p.equals("/repos/o/r/branches/main")) return js.setBody("{\"name\":\"main\",\"protected\":false}");
        if (contentsDenied && p.startsWith("/repos/o/r/contents") && m.equals("GET")) return js.setResponseCode(404).setBody("{\"message\":\"Not Found\"}");
        if (p.startsWith("/repos/o/r/contents/?")) return js.setBody("[{\"name\":\"README.md\",\"type\":\"file\"}]");
        if (p.startsWith("/repos/o/r/contents/.github/workflows/ci.yml")) {
          if (m.equals("PUT")) return js.setResponseCode(201).setBody("{\"content\":{}}");
          if (!hasWorkflowFile) return js.setResponseCode(404).setBody("{\"message\":\"Not Found\"}");
          String y = (workflowLegacy ? "# 由 7x24 的集成初始化生成：通过 PR 提交，需要你审阅后手动合并。\n" : "") + "name: CI\non:\n  push:\n" + (workflowDispatch ? "  workflow_dispatch:\n" : "") + "jobs: {}\n";
          return js.setBody("{\"sha\":\"s1\",\"content\":\"" + Base64.getMimeEncoder().encodeToString(y.getBytes()).replace("\r\n", "\\n") + "\"}");
        }
        if (p.equals("/repos/o/r/branches/main/protection")) return protectionStatus == 200 ? js.setBody("{\"url\":\"x\"}") : protectionStatus == 404 ? js.setResponseCode(404).setBody("{\"message\":\"Branch not protected\"}") : js.setResponseCode(protectionStatus).setBody("{\"message\":\"Upgrade to GitHub Pro or make this repository public to enable this feature.\"}");
        if (p.startsWith("/repos/o/r/git/ref/heads/")) return p.endsWith("x7x24/setup-ci-ci") ? js.setResponseCode(404).setBody("{\"message\":\"Not Found\"}") : js.setBody("{\"object\":{\"sha\":\"" + "a".repeat(40) + "\"}}");
        if (p.equals("/repos/o/r/git/refs") && m.equals("POST")) return js.setResponseCode(201).setBody("{}");
        if (p.equals("/repos/o/r/pulls") && m.equals("POST")) return js.setResponseCode(201).setBody("{\"number\":9,\"html_url\":\"https://github.com/o/r/pull/9\"}");
        return js.setResponseCode(404).setBody("{\"message\":\"Not Found\"}");
      }
    });
    try { server.start(); } catch (IOException e) { throw new RuntimeException(e); }
  }
  String url() { return "http://localhost:" + server.getPort(); }
  List<RecordedRequest> reqs(String method, String prefix) { return seen.stream().filter(r -> r.getMethod().equals(method) && r.getPath().startsWith(prefix)).toList(); }
}
