package com.x7x24.workflow;
import java.util.*;
import java.util.function.Function;
import static com.x7x24.workflow.Workflow.*;

/** Pure, deterministic compiler: no models, provider calls, or side effects. */
public final class PlanCompiler {
  private final Map<String,Definition> definitions; private final Function<String,ArtifactVersion> artifacts;
  public PlanCompiler(Map<String,Definition> definitions, Function<String,ArtifactVersion> artifacts) { this.definitions=definitions; this.artifacts=artifacts; }
  public Compiled compile(Plan source) {
    // Freeze caller-owned collections before hashing or execution.
    Plan p=com.x7x24.util.Json.parse(Canonical.json(source),Plan.class);
    List<Diagnostic> ds=new ArrayList<>(); Map<String,Node> nodes=new LinkedHashMap<>();
    if(p.schema()!=3) ds.add(d("","","SCHEMA","必须使用工作流 schema=3"));
    if(p.revisionId()==null || p.workspaceId()==null || p.projectId()==null || p.taskId()==null || p.baseSha()==null || p.policyDigest()==null || p.budgetUsd()<0 || !Double.isFinite(p.budgetUsd())) ds.add(d("","","PLAN_IDENTITY","缺少任务身份、仓库基线、策略或有效预算"));
    if(p.nodes()==null || p.nodes().isEmpty()) return new Compiled(p,List.of(),Canonical.digest(p),List.of(d("","","EMPTY_PLAN","计划至少需要一个节点")));
    if(p.nodes().size()>100) ds.add(d("","","RESOURCE_LIMIT","当前计划最多允许 100 个节点"));
    for(var n:p.nodes()) if(n.id()==null || nodes.putIfAbsent(n.id(),n)!=null) ds.add(d(n.id(),"","DUPLICATE_NODE","节点身份必须唯一"));
    Map<String,Set<String>> deps=new TreeMap<>(); nodes.keySet().forEach(id->deps.put(id,new TreeSet<>()));
    for(var n:nodes.values()) {
      var def=definitions.get(n.definitionRef());
      if(def==null || !def.available()) { ds.add(d(n.id(),"","CAPABILITY_UNAVAILABLE","节点合同或执行器不可用："+n.definitionRef())); continue; }
      Map<String,Port> ports=new LinkedHashMap<>(); def.inputs().forEach(port->ports.put(port.name(),port));
      var bindings=n.bindings()==null ? List.<Binding>of() : n.bindings();
      Set<String> identities=new HashSet<>();
      for(var b:bindings) {
        var port=ports.get(b.port());
        if(port==null) { ds.add(d(n.id(),b.port(),"UNKNOWN_PORT","输入端口未在合同中声明")); continue; }
        if(b.id()==null || !identities.add(b.id()) || b.ordinal()<0) ds.add(d(n.id(),b.port(),"BINDING_ID","输入绑定身份无效或重复"));
        boolean pin=b.versionId()!=null, up=b.producerNode()!=null && b.outputPort()!=null;
        if(pin==up || pin && (b.producerNode()!=null || b.outputPort()!=null)) { ds.add(d(n.id(),b.port(),"BINDING_SOURCE","输入必须且只能引用一个明确版本来源")); continue; }
        if(pin) {
          ArtifactVersion a=artifacts.apply(b.versionId());
          if(a==null) { ds.add(d(n.id(),b.port(),"ARTIFACT_MISSING","固定版本不存在或不可访问")); continue; }
          if(!Objects.equals(a.workspaceId(),p.workspaceId()) || !Objects.equals(a.projectId(),p.projectId())) ds.add(d(n.id(),b.port(),"ARTIFACT_SCOPE","产物属于其他工作区或项目"));
          if(a.type()!=port.type() || a.schema()!=port.schema()) ds.add(d(n.id(),b.port(),"ARTIFACT_TYPE","产物类型或 schema 版本不兼容"));
          if(port.type()==ArtifactType.RepoSnapshot && !Objects.equals(a.baseSha(),p.baseSha())) ds.add(d(n.id(),b.port(),"BASE_SHA","仓库快照与计划基线不一致"));
        } else {
          var producer=nodes.get(b.producerNode()); var pd=producer==null ? null : definitions.get(producer.definitionRef());
          var out=pd==null ? null : pd.outputs().stream().filter(o->o.name().equals(b.outputPort())).findFirst().orElse(null);
          if(out==null) ds.add(d(n.id(),b.port(),"OUTPUT_MISSING","生产者节点或输出端口不存在"));
          else if(out.type()!=port.type() || out.schema()!=port.schema()) ds.add(d(n.id(),b.port(),"ARTIFACT_TYPE","生产者输出的类型或版本不兼容"));
          if(producer!=null) deps.get(n.id()).add(producer.id());
        }
      }
      for(var port:ports.values()) {
        var bs=bindings.stream().filter(b->port.name().equals(b.port())).toList();
        Boolean condition=port.requirement()==Requirement.CONDITIONAL ? p.facts()==null ? null : p.facts().get(port.condition()) : Boolean.TRUE;
        if(port.requirement()==Requirement.CONDITIONAL && condition!=null) {
          var scopeBinding=bindings.stream().filter(b->"scopeEvidence".equals(b.port()) && b.versionId()!=null).findFirst().orElse(null);
          var scope=scopeBinding==null?null:artifacts.apply(scopeBinding.versionId());
          var evidence=scope==null?null:com.x7x24.util.Json.lenient(scope.content());
          if(evidence==null || !evidence.path("features").path(port.condition()).isBoolean() || evidence.path("features").path(port.condition()).asBoolean()!=condition)
            ds.add(d(n.id(),port.name(),"APPLICABILITY_EVIDENCE","条件必须有固定版本的范围证据，且与 scopeEvidence.features 一致"));
        }
        if(condition==null) ds.add(d(n.id(),port.name(),"APPLICABILITY_UNKNOWN","缺少条件所需的固定范围证据："+port.condition()));
        if(port.requirement()!=Requirement.OPTIONAL && Boolean.TRUE.equals(condition) && bs.isEmpty()) ds.add(d(n.id(),port.name(),"REQUIRED_INPUT","缺少必需输入"));
        if(bs.size()>port.maxItems() || bs.stream().map(Binding::ordinal).distinct().count()!=bs.size()) ds.add(d(n.id(),port.name(),"CARDINALITY","输入来源存在歧义、重复顺序或超过允许数量"));
      }
    }
    if(p.controls()!=null) for(var e:p.controls()) { if(!nodes.containsKey(e.from()) || !nodes.containsKey(e.to())) ds.add(d(e.to(),"","CONTROL_SOURCE","控制依赖指向不存在的节点")); else deps.get(e.to()).add(e.from()); }
    var remaining=new TreeMap<String,Set<String>>();deps.forEach((k,v)->remaining.put(k,new TreeSet<>(v))); List<String> order=new ArrayList<>();
    while(!remaining.isEmpty()) { String ready=remaining.entrySet().stream().filter(e->e.getValue().isEmpty()).map(Map.Entry::getKey).findFirst().orElse(null); if(ready==null) { ds.add(d("","","DEPENDENCY_CYCLE","输入或控制依赖图存在循环"));break; } order.add(ready);remaining.remove(ready);remaining.values().forEach(v->v.remove(ready)); }
    return new Compiled(p,List.copyOf(order),Canonical.digest(p),List.copyOf(ds));
  }
  private Diagnostic d(String n,String p,String c,String m) { return new Diagnostic(n==null ? "" : n,p,c,m); }
}
