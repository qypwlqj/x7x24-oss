package com.x7x24.skills;
import com.x7x24.db.Db;
import com.x7x24.tenant.Tenant;
import com.x7x24.util.Clk;
import org.springframework.stereotype.Component;
import java.util.*;
/** Revocation is a live restriction; immutable instruction snapshots cannot bypass it. */
@Component
public class SkillReleases {
 private final Db db;private final Clk clk;
 public SkillReleases(Db db,Clk clk){this.db=db;this.clk=clk;}
 public Set<String> revoked(){return new TreeSet<>(db.rows("SELECT skill_ref FROM skill_release_revocation WHERE workspace_id=?",Tenant.writeId()).stream().map(r->Db.str(r.get("skill_ref"))).toList());}
 public void revoke(String ref,String reason,String actor){if(ref==null || !ref.matches("[a-z0-9-]{1,60}@\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}") || reason==null || reason.isBlank())throw new IllegalArgumentException("请指定明确技能版本和撤销原因");if(!revoked().contains(ref))db.update("INSERT INTO skill_release_revocation(workspace_id,skill_ref,reason,actor,created_at) VALUES(?,?,?,?,?)",Tenant.writeId(),ref,reason,actor,clk.iso());}
}
