package com.x7x24.unit;
import com.x7x24.adapters.*;
import com.x7x24.util.Json;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class GitHubTransportTest {
  @Test void authorizationRecoveryRetriesAReadOnceButNeverReplaysAWrite() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(401).setBody("{}")); server.enqueue(new MockResponse().setBody("{}"));
      server.enqueue(new MockResponse().setResponseCode(401).setBody("{}")); server.start();
      var refreshes = new java.util.concurrent.atomic.AtomicInteger();
      var c = new GitHubClient(server.url("/").toString(), "synthetic-test", org.mockito.Mockito.mock(CallLog.class), h -> {}, 0, 0, ms -> {}, "fixture")
        .authorization(status -> {}, () -> { refreshes.incrementAndGet(); return true; });
      c.get("/repos/o/r", "read", "git"); assertEquals(2, server.getRequestCount()); assertEquals(1, refreshes.get());
      assertThrows(GitHubClient.GitHubException.class, () -> c.send("POST", "/comments", Map.of("body", "fixture"), "comment", "git", "key"));
      assertEquals(3, server.getRequestCount()); assertEquals(1, refreshes.get());
    }
  }
  @Test void objectEnvelopesFollowAllPagesAndRejectMissingRows() throws Exception {
    try(var server=new MockWebServer()){
      server.enqueue(new MockResponse().setHeader("Link","<http://localhost/ignored>; rel=\"next\"").setBody("{\"total_count\":2,\"jobs\":[{\"id\":1}]}"));
      server.enqueue(new MockResponse().setBody("{\"total_count\":2,\"jobs\":[{\"id\":2}]}"));
      server.enqueue(new MockResponse().setBody("{\"total_count\":1,\"jobs\":[]}"));server.start();
      var c=new GitHubClient(server.url("/").toString(),"synthetic-test",org.mockito.Mockito.mock(CallLog.class),h->{},0,0,ms->{},"fixture");
      assertEquals(2,c.objectArray("/jobs","jobs","jobs","ci").size());
      assertThrows(GitHubClient.GitHubException.class,()->c.objectArray("/jobs","jobs","jobs","ci"));
    }
  }
  @Test void ambiguousWriteMustNotBeRetried() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
      server.enqueue(new MockResponse().setBody("{}")); server.start();
      var c = new GitHubClient(server.url("/").toString(), "synthetic-test", org.mockito.Mockito.mock(CallLog.class), h -> {}, 2, 1000, ms -> {}, "test");
      assertThrows(GitHubClient.GitHubException.class, () -> c.send("POST", "/comments", Map.of("body", "x"), "comment", "git", "same-key"));
      assertEquals(1, server.getRequestCount());
    }
  }
}
