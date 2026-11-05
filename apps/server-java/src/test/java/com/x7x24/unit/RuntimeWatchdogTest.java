package com.x7x24.unit;
import com.x7x24.engine.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeWatchdogTest {
 @Test void watchdogRunsWhileEngineTickIsBlocked() throws Exception {
  var engine=mock(Engine.class);var props=new X7Props();props.engine.enabled=true;
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var watched=new CountDownLatch(1);
  when(engine.tick()).thenAnswer(call->{entered.countDown();release.await();return 0;});doAnswer(call->{watched.countDown();return null;}).when(engine).watchdog();
  var runtime=new com.x7x24.web.Runtime(engine,mock(Intake.class),props,mock(Discovery.class));var worker=Executors.newSingleThreadExecutor();
  try{worker.submit(runtime::tick);assertTrue(entered.await(2,TimeUnit.SECONDS));runtime.startWatchdog();assertTrue(watched.await(12,TimeUnit.SECONDS),"Independent watchdog must not wait for engine tick");}
  finally{release.countDown();runtime.close();worker.shutdownNow();}
 }
}
