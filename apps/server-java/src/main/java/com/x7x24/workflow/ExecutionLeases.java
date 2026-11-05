package com.x7x24.workflow;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.*;
import org.springframework.stereotype.Component;
import static com.x7x24.workflow.WorkflowStore.Blocked;
/** Expiry is loss of contact, not proof that a previous writer stopped. No automatic takeover. */
@Component
public class ExecutionLeases {
  private final Db db;private final Clk clk;
  public ExecutionLeases(Db db,Clk clk){this.db=db;this.clk=clk;}
  public long claim(String kind,String resource,String owner){
    return db.tx(()->{
      var r=db.row("SELECT * FROM execution_lease WHERE workspace_id=? AND resource_type=? AND resource_id=? FOR UPDATE",Tenant.writeId(),kind,resource);
      if(r==null){db.update("INSERT INTO execution_lease(workspace_id,resource_type,resource_id,owner_id,epoch,expires_at,heartbeat_at) VALUES(?,?,?,?,1,?,?)",Tenant.writeId(),kind,resource,owner,Clk.iso(clk.now()+60000),clk.iso());return 1L;}
      if(!"".equals(r.get("owner_id")))throw new Blocked("RECOVERY_REQUIRED","必须确认原执行者已停止或失去写入权限后才能恢复："+kind+":"+resource);
      long epoch=Db.l(r.get("epoch"))+1;
      db.update("UPDATE execution_lease SET owner_id=?,epoch=?,expires_at=?,heartbeat_at=? WHERE workspace_id=? AND resource_type=? AND resource_id=?",owner,epoch,Clk.iso(clk.now()+60000),clk.iso(),Tenant.writeId(),kind,resource);return epoch;
    });
  }
  public void assertOwner(String kind,String resource,String owner,long epoch){
    if(db.count("SELECT COUNT(*) FROM execution_lease WHERE workspace_id=? AND resource_type=? AND resource_id=? AND owner_id=? AND epoch=? AND expires_at>?",Tenant.writeId(),kind,resource,owner,epoch,clk.iso())!=1)throw new Blocked("STALE_EXECUTOR","执行权已过期或已被撤销，拒绝继续执行");
  }
  public void heartbeat(String kind,String resource,String owner,long epoch){assertOwner(kind,resource,owner,epoch);db.update("UPDATE execution_lease SET expires_at=?,heartbeat_at=? WHERE workspace_id=? AND resource_type=? AND resource_id=? AND owner_id=? AND epoch=?",Clk.iso(clk.now()+60000),clk.iso(),Tenant.writeId(),kind,resource,owner,epoch);}
  public void release(String kind,String resource,String owner,long epoch){db.update("UPDATE execution_lease SET owner_id='',expires_at=? WHERE workspace_id=? AND resource_type=? AND resource_id=? AND owner_id=? AND epoch=?",clk.iso(),Tenant.writeId(),kind,resource,owner,epoch);}
}
