package com.x7x24.unit;
import com.x7x24.web.Sse;
import com.x7x24.gov.AuditLog;
import com.x7x24.engine.TaskRepo;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SseLifecycleTest {
 @Test void contextCloseDrainsStreamsBeforeServerGracefulShutdown(){
  var context=new AnnotationConfigApplicationContext();context.registerBean(com.x7x24.web.SseHeartbeat.class,()->new com.x7x24.web.SseHeartbeat(15000));context.registerBean(Sse.class,()->new Sse(mock(AuditLog.class),mock(TaskRepo.class),context.getBean(com.x7x24.web.SseHeartbeat.class)));context.refresh();var sse=context.getBean(Sse.class);
  sse.open();assertEquals(1,sse.clients());context.close();assertEquals(0,sse.clients(),"Context shutdown must complete open streams before the server waits for requests");assertThrows(IllegalStateException.class,sse::open);
 }
}
