package com.x7x24.notification;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationMailTest {
  @Test void sendsThroughLocalSmtpWithConfiguredSenderAndTaskLink() throws Exception {
    try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(10_000);
      var executor=Executors.newSingleThreadExecutor();
      try {
        var received=executor.submit(()->{
          try(var socket=server.accept(); var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)); var writer=new PrintWriter(socket.getOutputStream(),true,StandardCharsets.UTF_8)) {
            socket.setSoTimeout(10_000); writer.print("220 local-test SMTP\r\n");writer.flush();
            var data=new StringBuilder(); boolean collecting=false;
            for(String line;(line=reader.readLine())!=null;) {
              if(collecting) { if(line.equals(".")) { collecting=false;writer.print("250 accepted\r\n"); } else data.append(line).append('\n'); }
              else if(line.startsWith("DATA")) { collecting=true;writer.print("354 send data\r\n"); }
              else if(line.startsWith("QUIT")) { writer.print("221 bye\r\n");writer.flush();break; }
              else writer.print("250 OK\r\n");
              writer.flush();
            }
            return data.toString();
          }
        });
        var env=new MockEnvironment().withProperty("x7x24.notifications.smtp.host",server.getInetAddress().getHostAddress()).withProperty("x7x24.notifications.smtp.port",String.valueOf(server.getLocalPort())).withProperty("x7x24.notifications.smtp.starttls","false").withProperty("x7x24.notifications.from","sender@example.invalid").withProperty("x7x24.notifications.app-url","http://localhost:8787");
        var mail=new NotificationMail(env); assertTrue(mail.ready());
        mail.send("recipient@example.invalid","Task complete","notification-test\n"+mail.taskUrl("task-1"));
        String message=received.get(10,TimeUnit.SECONDS);
        assertTrue(message.contains("From: sender@example.invalid")); assertTrue(message.contains("To: recipient@example.invalid")); assertTrue(message.contains("Subject: Task complete")); assertTrue(message.contains("notification-test")); assertTrue(message.contains("http://localhost:8787/t/task-1"));
      } finally { executor.shutdownNow(); }
    }
  }
  @Test void remainsOptionalUntilSenderHostAndFrontendUrlAreConfigured() {
    assertFalse(new NotificationMail(new MockEnvironment()).ready());
    var env=new MockEnvironment().withProperty("x7x24.notifications.smtp.host","smtp.example.invalid").withProperty("x7x24.notifications.from","sender@example.invalid").withProperty("x7x24.notifications.app-url","javascript:invalid");
    assertFalse(new NotificationMail(env).ready());
  }
}
