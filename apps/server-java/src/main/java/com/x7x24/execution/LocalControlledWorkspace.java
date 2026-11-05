package com.x7x24.execution;
import com.x7x24.util.Json;
import com.x7x24.workflow.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import static com.x7x24.execution.CodingExecutor.*;
/** No subprocesses, sockets, plugins or arbitrary scripts. Only these bounded file/check primitives exist. */
public final class LocalControlledWorkspace implements AutoCloseable {
 private final Path root;private final JobSpec spec;private final Runnable authority;private final BooleanSupplier cancelled;private final List<Check> checks=new ArrayList<>();private final List<Event> events=new ArrayList<>();private long seq;
 public LocalControlledWorkspace(Path root,JobSpec spec,Runnable authority,BooleanSupplier cancelled){this.root=root.toAbsolutePath().normalize();this.spec=spec;this.authority=authority;this.cancelled=cancelled;
  try{for(Path current=this.root;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw new IllegalArgumentException("临时执行目录不能经过符号链接");
   if(Files.exists(this.root))try(var entries=Files.list(this.root)){if(entries.findAny().isPresent())throw new IllegalArgumentException("只能使用新建的空临时目录，不能接管已有文件");}
  }catch(java.io.IOException e){throw new IllegalArgumentException("无法核验临时目录归属",e);}
 }
 public Result execute(Consumer<Event> append){
  try{
   validate();Files.createDirectories(root);
   for(var e:spec.baseFiles().entrySet())write(e.getKey(),e.getValue(),false);
   for(var step:spec.steps()){
    guard();String text=read(step.path());String detail=step.path();
    switch(step.action()){
     case READ -> { if(text==null)throw new IllegalArgumentException("本地输入文件不存在"); }
     case SEARCH -> {if(text==null || step.search()==null)throw new IllegalArgumentException("检索输入不完整");detail += ":matches="+(text.contains(step.search())?1:0);}
     case EDIT -> {if(text==null || step.search()==null || step.search().isEmpty() || text.indexOf(step.search())<0 || text.indexOf(step.search(),text.indexOf(step.search())+1)>=0)throw new IllegalArgumentException("替换片段必须在文件中恰好出现一次");write(step.path(),text.replace(step.search(),Objects.toString(step.replacement(),"")),true);}
     case PUT -> write(step.path(),Objects.toString(step.replacement(),""),true);
     case ASSERT_CONTAINS,ASSERT_JSON -> {
      if(step.acceptanceId()==null || !spec.acceptanceIds().contains(step.acceptanceId()))throw new IllegalArgumentException("检查必须关联已固定的验收条目");
      boolean pass=step.action()==Action.ASSERT_JSON?text!=null&&Json.lenient(text)!=null:text!=null&&step.search()!=null&&text.contains(step.search());
      checks.add(new Check(step.acceptanceId(),step.action()==Action.ASSERT_JSON?"local-json@1":"local-text@1",pass?"PASS":"FAIL",hash(),"builtin:"+step.action().name().toLowerCase(Locale.ROOT),now()));
     }
    }
    var event=new Event(++seq,step.action().name(),hash(),detail,now());events.add(event);append.accept(event);
   }
   guard();String tree=hash();var missing=new ArrayList<String>();
   for(String ac:spec.acceptanceIds())if(checks.stream().noneMatch(c->c.acceptanceId().equals(ac)&&c.subjectTreeHash().equals(tree)) || checks.stream().anyMatch(c->c.acceptanceId().equals(ac)&&c.subjectTreeHash().equals(tree)&&!c.result().equals("PASS")))missing.add(ac);
   if(spec.acceptanceIds().isEmpty())missing.add("NO_REQUIRED_CHECKS");
   return result(missing.isEmpty()?State.SUCCEEDED:State.FAILED,missing);
  }catch(java.util.concurrent.CancellationException e){return result(State.CANCELLED,List.of("CANCELLED"));}
  catch(Exception e){return result(State.FAILED,List.of(e.getClass().getSimpleName()+": "+Objects.toString(e.getMessage(),"")));}
 }
 private void validate(){
  if(spec.protocolVersion()!=1 || !"DENY".equals(spec.networkPolicy()) || spec.limits()==null || spec.limits().maxFiles()<1 || spec.limits().maxFiles()>100 || spec.limits().maxBytes()<1 || spec.limits().maxBytes()>1_000_000 || spec.limits().maxSteps()<1 || spec.limits().maxSteps()>200 || spec.steps()==null || spec.steps().size()>spec.limits().maxSteps() || spec.baseFiles()==null)throw new IllegalArgumentException("本地执行合同超出支持的资源或网络边界");
  if(spec.baseFiles().size()>spec.limits().maxFiles())throw new IllegalArgumentException("输入文件数量超过限制");
  for(var step:spec.steps())if(step==null || step.action()==null)throw new IllegalArgumentException("执行步骤不完整");
 }
 private Path path(String relative)throws java.io.IOException{
  if(relative==null || relative.isBlank() || relative.startsWith("/") || relative.contains("\\") || Arrays.asList(relative.split("/")).contains("..") || relative.matches("(?i).*(^|/)(\\.git|\\.github|\\.env[^/]*|scripts|CODEOWNERS)(/.*)?"))throw new IllegalArgumentException("本地路径越界或触及禁止目录");
  Path p=root.resolve(relative).normalize();if(!p.startsWith(root) || p.equals(root))throw new IllegalArgumentException("本地路径越界");
  for(Path current=p;current!=null&&current.startsWith(root);current=current.getParent())if(Files.isSymbolicLink(current))throw new IllegalArgumentException("本地执行不允许符号链接");
  return p;
 }
 private String read(String name)throws java.io.IOException{Path p=path(name);return Files.exists(p,LinkOption.NOFOLLOW_LINKS)?Files.readString(p,StandardCharsets.UTF_8):null;}
 private void write(String name,String content,boolean enforceScope)throws java.io.IOException{
  guard();if(enforceScope && spec.writeScopes().stream().noneMatch(scope->name.equals(scope)||scope.endsWith("/")&&name.startsWith(scope)))throw new IllegalArgumentException("修改不在批准的文件范围内");
  Path p=path(name);int size=content.getBytes(StandardCharsets.UTF_8).length;
  Map<String,String> files=files();files.put(name,content);if(files.size()>spec.limits().maxFiles() || size>spec.limits().maxBytes() || files.values().stream().mapToInt(s->s.getBytes(StandardCharsets.UTF_8).length).sum()>spec.limits().maxBytes())throw new IllegalArgumentException("文件数量或内容超过资源上限");
  Files.createDirectories(p.getParent());Files.writeString(p,content,StandardCharsets.UTF_8);
 }
 private void guard(){authority.run();if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();if(System.currentTimeMillis()>spec.limits().deadlineAt())throw new IllegalArgumentException("本地执行已到截止时间");}
 private Map<String,String> files(){var out=new TreeMap<String,String>();if(!Files.exists(root))return out;try(var stream=Files.walk(root)){for(Path p:stream.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)).toList()){String relative=root.relativize(p).toString();path(relative);out.put(relative,Files.readString(p));}}catch(java.io.IOException e){throw new IllegalStateException("读取本地执行材料失败",e);}return out;}
 private String hash(){return Canonical.digest(files());}
 private Result result(State state,List<String> missing){var content=files();String tree=Canonical.digest(content);return new Result(state,spec.baseSha(),tree,content,List.copyOf(checks),List.copyOf(events),"local-tree:"+tree,List.copyOf(missing),0);}
 private static String now(){return com.x7x24.util.Clk.iso(System.currentTimeMillis());}
 @Override public void close(){if(Files.exists(root))try(var stream=Files.walk(root)){for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(java.io.IOException e){throw new IllegalStateException("清理本地临时执行目录失败",e);}}
}
