CREATE TRIGGER skill_evaluation_no_update BEFORE UPDATE ON skill_evaluation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
CREATE TRIGGER skill_evaluation_no_delete BEFORE DELETE ON skill_evaluation FOR EACH ROW CALL "com.x7x24.db.AppendOnlyTrigger";
