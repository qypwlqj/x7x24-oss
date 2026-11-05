-- H2 uses application scope predicates; PostgreSQL V24 enforces RLS as well.
SELECT 1;

CREATE TRIGGER artifact_version_no_update BEFORE UPDATE ON artifact_version FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_version_no_delete BEFORE DELETE ON artifact_version FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_lineage_no_update BEFORE UPDATE ON artifact_lineage FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_lineage_no_delete BEFORE DELETE ON artifact_lineage FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_validation_no_update BEFORE UPDATE ON artifact_validation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_validation_no_delete BEFORE DELETE ON artifact_validation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_invalidation_no_update BEFORE UPDATE ON artifact_invalidation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";

CREATE TRIGGER artifact_invalidation_no_delete BEFORE DELETE ON artifact_invalidation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
