package com.x7x24.skills;
import com.x7x24.workflow.Canonical;
import com.x7x24.workflow.WorkflowStore.Blocked;
import java.util.*;
/** Pure v2 package/binding resolver. Requirements never grant a tool or permission. */
public final class SkillResolver {
 public enum Scope { PROJECT, ROLE, NODE_INSTANCE }
 public enum Enforcement { REQUIRED, OPTIONAL }
 public enum Truth { TRUE, FALSE, UNKNOWN }
 public record Parameter(String type,Object defaultValue,boolean overridable) {}
 public record Dependency(String skillId,String constraint) {}
 public record Version(String id,String version,String instructions,String digest,List<Dependency> dependencies,Map<String,Parameter> parameters,Set<String> requiredCapabilities) {}
 public record Binding(String id,String workspaceId,String projectId,Scope scope,String target,String roleSelector,String skillId,String constraint,Enforcement enforcement,String condition,Map<String,Object> parameters) {}
 public record Domain(String workspaceId,String projectId,String nodeId,String roleBindingId,String roleSlot,String roleId) {}
 public record Facts(Map<String,Boolean> values,boolean complete,String evidenceDigest) {}
 public record Obligation(String bindingId,Enforcement enforcement,String condition) {}
 public record Resolved(String id,String version,String packageDigest,String instructions,Set<String> requiredCapabilities,Map<String,Object> parameters,List<Obligation> obligations,List<String> dependencies) {}
 public record Lock(List<Resolved> skills,String digest) {}
 public record Evaluation(String bindingId,String skillRef,Truth applicability,String state,String evidenceDigest,Set<String> missingCapabilities) {}
 private static final Set<String> PROTECTED=Set.of("allowedTools","permissions","required","validators","budgetLimit","networkPolicy");
 public Lock resolve(Domain domain,List<Binding> candidates,Map<String,Version> catalogue,Set<String> revoked){
  var bindings=candidates.stream().filter(b->matches(domain,b)).sorted(Comparator.comparing((Binding b)->b.scope().ordinal()).thenComparing(Binding::id)).toList();
  Map<String,List<String>> constraints=new TreeMap<>();for(var b:bindings)constraints.computeIfAbsent(b.skillId(),k->new ArrayList<>()).add(b.constraint());
  var selected=solve(constraints,new TreeMap<>(),catalogue,revoked,0);if(selected==null)throw new Blocked("VERSION_CONFLICT","技能版本约束或依赖无法同时满足，请检查绑定来源");
  detectCycles(selected);
  var resolved=new ArrayList<Resolved>();
  for(var en:selected.entrySet()){
   var version=en.getValue();Map<String,Object> parameters=new TreeMap<>();version.parameters().forEach((k,v)->{if(v.defaultValue()!=null)parameters.put(k,v.defaultValue());});
   Map<String,Integer> priority=new HashMap<>();Map<String,Object> atPriority=new HashMap<>();var obligations=new ArrayList<Obligation>();
   for(var b:bindings)if(b.skillId().equals(en.getKey())){
    obligations.add(new Obligation(b.id(),b.enforcement(),b.condition()));
    for(var p:(b.parameters()==null?Map.<String,Object>of():b.parameters()).entrySet()){
     var schema=version.parameters().get(p.getKey());if(schema==null || PROTECTED.contains(p.getKey()) || !schema.overridable()&&!Objects.equals(schema.defaultValue(),p.getValue()))throw new Blocked("PARAMETER_DENIED","技能参数不在允许覆盖的范围内："+p.getKey());
     if(!type(schema.type(),p.getValue()))throw new Blocked("PARAMETER_TYPE","技能参数类型不匹配："+p.getKey());
     int level=b.scope().ordinal();if(priority.getOrDefault(p.getKey(),-1)==level && !Canonical.digest(atPriority.get(p.getKey())).equals(Canonical.digest(p.getValue())))throw new Blocked("PARAMETER_CONFLICT","同一作用域的技能参数存在冲突："+p.getKey());
     priority.put(p.getKey(),level);atPriority.put(p.getKey(),p.getValue());parameters.put(p.getKey(),p.getValue());
    }
   }
   var deps=version.dependencies().stream().map(d->d.skillId()+"@"+selected.get(d.skillId()).version()).sorted().toList();
   resolved.add(new Resolved(version.id(),version.version(),version.digest(),version.instructions(),version.requiredCapabilities(),Map.copyOf(parameters),List.copyOf(obligations),deps));
  }
  return new Lock(List.copyOf(resolved),Canonical.digest(resolved));
 }
 public List<Evaluation> evaluate(Lock lock,Facts facts,Set<String> capabilities,Set<String> revoked){
  var evaluations=new ArrayList<Evaluation>();Map<String,Resolved> by=new HashMap<>();lock.skills().forEach(s->by.put(s.id(),s));
  for(var skill:lock.skills())for(var obligation:skill.obligations()){
   Truth truth=obligation.condition()==null?Truth.TRUE:!facts.complete()||facts.evidenceDigest()==null||(!facts.values().containsKey(obligation.condition()) || facts.values().get(obligation.condition())==null)?Truth.UNKNOWN:Boolean.TRUE.equals(facts.values().get(obligation.condition()))?Truth.TRUE:Truth.FALSE;
   Set<String> refs=new TreeSet<>(),required=new TreeSet<>();closure(skill,by,refs,required);
   boolean retired=refs.stream().anyMatch(revoked::contains);var missing=new TreeSet<>(required);missing.removeAll(capabilities);
   String state=retired?"BLOCKED_REVOKED":truth==Truth.FALSE?"NOT_APPLICABLE":truth==Truth.UNKNOWN?(obligation.enforcement()==Enforcement.REQUIRED?"BLOCKED_MISSING_APPLICABILITY_EVIDENCE":"UNKNOWN"):!missing.isEmpty()?(obligation.enforcement()==Enforcement.REQUIRED?"BLOCKED_CAPABILITY":"SKIPPED_CAPABILITY"):"ACTIVE";
   evaluations.add(new Evaluation(obligation.bindingId(),skill.id()+"@"+skill.version(),truth,state,facts.evidenceDigest(),Set.copyOf(missing)));
  }
  return List.copyOf(evaluations);
 }
 private void closure(Resolved skill,Map<String,Resolved> by,Set<String> refs,Set<String> capabilities){if(!refs.add(skill.id()+"@"+skill.version()))return;capabilities.addAll(skill.requiredCapabilities());for(String dep:skill.dependencies())closure(by.get(dep.substring(0,dep.lastIndexOf('@'))),by,refs,capabilities);}
 private boolean matches(Domain d,Binding b){
  if(!Objects.equals(d.workspaceId(),b.workspaceId()) || !Objects.equals(d.projectId(),b.projectId()))throw new Blocked("SKILL_SCOPE","技能绑定不能跨工作区或项目引用");
  if(b.roleSelector()!=null && !b.roleSelector().equals(d.roleSlot())&&!b.roleSelector().equals(d.roleId()))return false;
  return b.scope()==Scope.PROJECT || b.scope()==Scope.NODE_INSTANCE&&Objects.equals(b.target(),d.nodeId()) || b.scope()==Scope.ROLE&&Objects.equals(b.target(),d.roleBindingId());
 }
 private Map<String,Version> solve(Map<String,List<String>> constraints,Map<String,Version> assigned,Map<String,Version> catalogue,Set<String> revoked,int depth){
  if(depth>64)throw new Blocked("SKILL_LIMIT","技能依赖数量超过当前上限");
  for(var e:assigned.entrySet())if(constraints.getOrDefault(e.getKey(),List.of()).stream().anyMatch(c->!satisfies(e.getValue().version(),c)))return null;
  String next=constraints.keySet().stream().filter(k->!assigned.containsKey(k)).sorted().findFirst().orElse(null);if(next==null)return assigned;
  var versions=catalogue.values().stream().filter(v->v.id().equals(next)&&!revoked.contains(v.id()+"@"+v.version())&&constraints.get(next).stream().allMatch(c->satisfies(v.version(),c))).sorted((a,b)->compare(b.version(),a.version())).toList();
  for(var version:versions){
   var choice=new TreeMap<>(assigned);choice.put(next,version);Map<String,List<String>> expanded=new TreeMap<>();constraints.forEach((k,v)->expanded.put(k,new ArrayList<>(v)));
   for(var dependency:version.dependencies())expanded.computeIfAbsent(dependency.skillId(),k->new ArrayList<>()).add(dependency.constraint());
   var result=solve(expanded,choice,catalogue,revoked,depth+1);if(result!=null)return result;
  }return null;
 }
 private void detectCycles(Map<String,Version> versions){Set<String> done=new HashSet<>(),visiting=new HashSet<>();for(String id:versions.keySet())visit(id,versions,done,visiting);}
 private void visit(String id,Map<String,Version> versions,Set<String> done,Set<String> visiting){if(done.contains(id))return;if(!visiting.add(id))throw new Blocked("DEPENDENCY_CYCLE","技能依赖存在循环："+id);for(var d:versions.get(id).dependencies())visit(d.skillId(),versions,done,visiting);visiting.remove(id);done.add(id);}
 private static boolean type(String type,Object value){return switch(type){case "STRING"->value instanceof String;case "BOOLEAN"->value instanceof Boolean;case "NUMBER"->value instanceof Number;case "ARRAY"->value instanceof List;case "OBJECT"->value instanceof Map;default->false;};}
 private static boolean satisfies(String version,String constraint){if(constraint==null)return false;if(constraint.startsWith("^")){String base=constraint.substring(1);if(base.matches("\\d+"))base+=".0.0";return compare(version,base)>=0&&version.split("\\.")[0].equals(base.split("\\.")[0]);}return version.equals(constraint);}
 private static int compare(String a,String b){if(!a.matches("\\d+\\.\\d+\\.\\d+")||!b.matches("\\d+\\.\\d+\\.\\d+"))throw new Blocked("VERSION_FORMAT","技能版本必须采用受支持的三段数字版本");String[] aa=a.split("\\."),bb=b.split("\\.");for(int i=0;i<3;i++){int c=Integer.compare(Integer.parseInt(aa[i]),Integer.parseInt(bb[i]));if(c!=0)return c;}return 0;}
}
