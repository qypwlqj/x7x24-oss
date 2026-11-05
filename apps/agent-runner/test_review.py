"""逐行审查 job：用假 docker + 假 ocr 跑完整流程（clone → 检出 PR 头 → 按官方 skill 的命令跑 ocr → 回传 JSON）。"""
import hashlib, json, os, stat, subprocess, tempfile, time, unittest
import runnerd

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = "fake-llm-token-0123456789abcdef"


def git(*a, cwd):
    subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", *a], cwd=cwd, check=True, capture_output=True)


class ReviewJobTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        src = os.path.join(self.tmp, "src"); os.makedirs(src)
        git("init", "-q", "-b", "main", cwd=src)
        open(os.path.join(src, "app.js"), "w").write("export const a = 1;\n"); git("add", ".", cwd=src); git("commit", "-qm", "init", cwd=src)
        git("checkout", "-qb", "feat", cwd=src)
        open(os.path.join(src, "app.js"), "a").write("export const b = 2;\n"); git("commit", "-qam", "feat", cwd=src)
        self.head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=src, capture_output=True, text=True).stdout.strip()
        self.repo = "file://" + src
        self.args = os.path.join(self.tmp, "args.json")
        self.mode = os.path.join(self.tmp, "mode")
        open(self.mode, "w").write("ok")
        # 假 docker 外面再包一层，把测试开关带进去（runnerd 只给 docker 传 PATH 和 OCR_LLM_*）
        wrap = os.path.join(self.tmp, "docker")
        open(wrap, "w").write(f"#!/bin/sh\nFAKE_OCR_ARGS={self.args} FAKE_OCR_MODE=$(cat {self.mode}) exec python3 {HERE}/fake_docker.py \"$@\"\n")
        os.chmod(wrap, os.stat(wrap).st_mode | stat.S_IEXEC)
        ocr = os.path.join(HERE, "fake_ocr.py")
        sha = hashlib.sha256(open(ocr, "rb").read()).hexdigest()
        cfg = dict(runnerd.CFG, key="k" * 40, bind="127.0.0.1", docker=wrap, work=os.path.join(self.tmp, "jobs"), cache=os.path.join(self.tmp, "cache"),
                   ocr_bin=ocr, ocr_sha256=sha, ocr_work=os.path.join(self.tmp, "ocr"), allow_file_repos=True, clone_attempts=1)
        self.r = runnerd.Runner(cfg)

    def spec(self, key, **kw):
        s = {"jobKey": key, "reviewKey": "task-1:CODE_REVIEW", "repo": self.repo, "baseRef": "main", "headSha": self.head,
             "llm": {"url": "https://api.example.com/v1", "token": TOKEN, "model": "kimi-k2.7-code", "protocol": "openai"},
             "background": "新增 b 常量", "maxTokensBudget": 600000, "timeoutMin": 15, "concurrency": 8,
             "limits": {"wallClockSec": 600, "maxTurns": 1, "memory": "1g", "cpus": 1}}
        s.update(kw); return s

    def wait(self, view):
        job = self.r.jobs[view["id"]]
        for _ in range(1200):
            if job.state not in ("QUEUED", "RUNNING"):
                return job
            time.sleep(0.05)
        self.fail("job 没结束")

    def test_runs_ocr_like_the_official_skill_and_returns_json(self):
        code, v = self.r.submit(self.spec("j1"), "review"); self.assertEqual(200, code, v)
        job = self.wait(v)
        self.assertEqual("SUCCEEDED", job.state, job.error)
        a = json.load(open(self.args))
        args = a["args"]
        self.assertEqual(["review", "--audience", "agent", "--format", "json"], args[:5])
        self.assertEqual("origin/main", args[args.index("--from") + 1]); self.assertEqual(self.head, args[args.index("--to") + 1])
        self.assertEqual("新增 b 常量", args[args.index("--background") + 1]); self.assertEqual("600000", args[args.index("--max-tokens-budget") + 1])
        self.assertNotIn("--resume", args)
        self.assertTrue(a["hasToken"], "密钥经环境变量交给 ocr"); self.assertEqual("kimi-k2.7-code", a["model"]); self.assertEqual("openai", a["protocol"])
        self.assertTrue(a["cwd"].endswith("/repo"))
        doc = job.result["ocr"]
        self.assertEqual("complete", doc["status"]); self.assertTrue(doc["session_id"])
        self.assertTrue(all("thinking" not in c for c in doc["comments"]))
        self.assertEqual("v1.13.5", job.result["ocrVersion"])
        blob = json.dumps(job.view(), ensure_ascii=False) + json.dumps(job.logs, ensure_ascii=False)
        self.assertNotIn(TOKEN, blob, "密钥不进结果和日志"); self.assertNotIn("llm", job.spec)

    def test_resume_reuses_same_repo_dir_and_home(self):
        open(self.mode, "w").write("fail")
        job = self.wait(self.r.submit(self.spec("j2"), "review")[1])
        self.assertEqual("SUCCEEDED", job.state, "ocr 失败但写出了结果：交给后端判断")
        self.assertEqual("failed", job.result["ocr"]["status"]); self.assertEqual(1, job.result["exitCode"])
        first = json.load(open(self.args))
        sid = job.result["ocr"]["session_id"]
        open(self.mode, "w").write("ok")
        job2 = self.wait(self.r.submit(self.spec("j3", resume=sid, llm={"url": "https://api.deepseek.com/v1", "token": TOKEN, "model": "deepseek-chat"}), "review")[1])
        self.assertEqual("SUCCEEDED", job2.state, job2.error)
        second = json.load(open(self.args))
        self.assertEqual(sid, second["args"][second["args"].index("--resume") + 1])
        self.assertEqual(first["cwd"], second["cwd"], "--resume 依赖同一个仓库路径"); self.assertEqual(first["home"], second["home"])
        self.assertEqual("deepseek-chat", second["model"])
        self.assertEqual(sid, job2.result["ocr"]["resume"]["resumed_from"])

    def test_no_output_is_failed(self):
        open(self.mode, "w").write("nofile")
        job = self.wait(self.r.submit(self.spec("j4"), "review")[1])
        self.assertEqual("FAILED", job.state); self.assertIn("没有输出结果", job.error)

    def test_rejects_bad_specs_and_unpinned_binary(self):
        self.assertEqual(400, self.r.submit(self.spec("j5", headSha="main; rm -rf /"), "review")[0])
        self.assertEqual(400, self.r.submit(self.spec("j6", llm={"url": "file:///etc/passwd", "token": TOKEN, "model": "m"}), "review")[0])
        self.assertEqual(400, self.r.submit(self.spec("j7", resume="--evil"), "review")[0])
        self.r.cfg["ocr_sha256"] = "0" * 64
        job = self.wait(self.r.submit(self.spec("j8"), "review")[1])
        self.assertEqual("FAILED", job.state); self.assertIn("校验和", job.error)


if __name__ == "__main__":
    unittest.main()
