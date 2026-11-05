CREATE TRIGGER skill_release_revocation_no_update BEFORE UPDATE ON skill_release_revocation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER skill_release_revocation_no_delete BEFORE DELETE ON skill_release_revocation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
