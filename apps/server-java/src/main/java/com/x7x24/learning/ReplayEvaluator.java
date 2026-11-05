package com.x7x24.learning;

import com.x7x24.adapters.Redactor;
import com.x7x24.util.*;
import com.x7x24.workflow.Canonical;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Tests are frozen separately from model input. Project commands only execute in a credential-free container. */
@Component
public class ReplayEvaluator implements Learning.Evaluator {
  @Value("${x7x24.learning.docker-image:}") String image="";
  @Value("${x7x24.learning.test-command:}") String command="";
  @Value("${x7x24.learning.test-timeout-seconds:30}") int timeout;
  public String configurationDigest() {return Canonical.digest(List.of("learning-evaluator@1",image,command,timeout));}
  public double maximumMinutes(String scope) {return "PROJECT_TESTS".equals(scope)?Math.max(0,timeout)/60d:0;}
  static void path(String path) {
    if(path==null || !path.matches("[A-Za-z0-9_][A-Za-z0-9_./@ -]{0,199}") || path.contains("..") || path.contains("//"))
      throw new IllegalArgumentException("实验文件路径不正确");
  }
  static boolean protectedPath(String p) {
    return p.matches("(?i)(.*(?:^|/)(?:tests?|__tests__|specs?|scripts|deploy|\\.github|\\.git)(?:/|$).*|.*(?:[._-](?:test|spec)\\.[^/]+|_test\\.go)|.*(?:CODEOWNERS|(?:package-lock|pnpm-lock|yarn.lock).*))");
  }
  @Override public Learning.Evaluation evaluate(Learning.Case c,Map<String,String> files,String scope) {
    long started=System.nanoTime();List<String> failures=new ArrayList<>();
    for(var check:c.checks()) {
      String actual=files.get(check.path());boolean ok=false;
      try { ok=actual!=null && switch(check.kind()) {
        case "CONTAINS" -> actual.contains(check.expected());
        case "EXACT" -> actual.equals(check.expected());
        case "JSON_EQUALS" -> Json.tree(actual).equals(Json.tree(check.expected()));
        default -> false;
      }; }catch(RuntimeException ignore) { /* Invalid JSON is a failed assertion. */ }
      if(!ok)failures.add("断言未通过："+check.path()+" / "+check.kind());
    }
    String testOutput="NOT_RUN";
    if("PROJECT_TESTS".equals(scope)) {
      if(image==null || !image.matches("[A-Za-z0-9./:_-]+@sha256:[a-f0-9]{64}") || command==null || command.isBlank() || timeout<1 || timeout>300)
        throw new IllegalArgumentException("项目测试需要固定摘要的隔离镜像、测试命令和有效超时");
      testOutput=container(files,failures);
    } else if(!"FILE_ASSERTIONS".equals(scope))throw new IllegalArgumentException("评估范围不正确");
    long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
    return new Learning.Evaluation(failures.isEmpty(),scope,Canonical.digest(List.of(configurationDigest(),c,files,testOutput,failures)),List.copyOf(failures),millis);
  }
  private String container(Map<String,String> files,List<String> failures) {
    Path root=null;Process process=null;String name="x7-learning-"+UUID.randomUUID();
    try {
      root=Files.createTempDirectory("x7-learning-");Path work=Files.createDirectory(root.resolve("work"));
      for(var e:files.entrySet()) {path(e.getKey());Path file=work.resolve(e.getKey());Files.createDirectories(file.getParent());Files.writeString(file,e.getValue());}
      try(var walk=Files.walk(work)){for(Path p:walk.toList())Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString(Files.isDirectory(p)?"rwxr-xr-x":"rw-r--r--"));}
      // Host input is read-only. All test writes stay in capped tmpfs, not an unbounded host mount.
      var args=List.of("docker","run","--rm","--name",name,"--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges","--memory=512m","--memory-swap=512m","--cpus=1","--pids-limit=128","--log-driver=none","--user=10001:10001","--tmpfs=/tmp:rw,nosuid,nodev,size=128m","--tmpfs=/workspace:rw,nosuid,nodev,size=256m,uid=10001,gid=10001,mode=0700","--mount=type=bind,src="+work+",dst=/input,readonly","--workdir=/workspace",image,"sh","-c","cp -R /input/. /workspace/ && exec sh -lc \"$1\"","x7-learning",command);
      var builder=cleanBuilder(args);
      builder.redirectErrorStream(true);process=builder.start();var stream=process.getInputStream();var captured=new java.util.concurrent.atomic.AtomicReference<>("");
      Thread reader=Thread.ofVirtual().start(()->{try(stream){captured.set(Redactor.redact(new String(stream.readNBytes(16000),StandardCharsets.UTF_8)));stream.transferTo(java.io.OutputStream.nullOutputStream());}catch(java.io.IOException ignored){}});
      if(!process.waitFor(timeout,TimeUnit.SECONDS)) {failures.add("隔离测试超时");return "TIMED_OUT";}
      if(process.exitValue()!=0)failures.add("项目测试未通过，退出码 "+process.exitValue());
      reader.join(1000);return captured.get();
    }catch(Exception e) {
      if(e instanceof InterruptedException)Thread.currentThread().interrupt();
      throw new IllegalArgumentException("隔离测试运行器不可用："+e.getClass().getSimpleName());
    }finally {
      if(process!=null && process.isAlive())process.destroyForcibly();
      if(process!=null)try {var stop=cleanBuilder(List.of("docker","rm","-f",name)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();if(!stop.waitFor(5,TimeUnit.SECONDS))stop.destroyForcibly();}catch(Exception ignored) {}
      if(root!=null)try(var walk=Files.walk(root)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(Exception ignored) {}
    }
  }
  private static ProcessBuilder cleanBuilder(List<String> command) {
    var builder=new ProcessBuilder(command);String executablePath=System.getenv("PATH");builder.environment().clear();if(executablePath!=null)builder.environment().put("PATH",executablePath);return builder;
  }
}
