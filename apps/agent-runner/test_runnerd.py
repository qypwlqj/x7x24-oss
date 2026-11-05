import json, os, subprocess, tempfile, threading, time, unittest, urllib.request, uuid
import runnerd

KEY = "k" * 40


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = os.path.join(self.tmp, "src")
        os.makedirs(self.repo)
        g = ["git", "-C", self.repo]
        subprocess.run(["git", "init", "-q", "-b", "main", self.repo], check=True)
        open(os.path.join(self.repo, "math.js"), "w").write("export const add = (a, b) => a - b;\n")
        open(os.path.join(self.repo, "old.js"), "w").write("x\n")
        subprocess.run(g + ["add", "."], check=True)
        subprocess.run(g + ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init"], check=True)
        envf = os.path.join(self.tmp, "model.env"); open(envf, "w").write("ANTHROPIC_AUTH_TOKEN=" + ("sk-" + "SECRETSECRETSECRET123") + "\n")
        self.killed = os.path.join(self.tmp, "killed")
        os.environ["FAKE_KILLED"] = self.killed
        cfg = dict(runnerd.CFG, bind="127.0.0.1", port=0, key=KEY, work=os.path.join(self.tmp, "jobs"), cache=os.path.join(self.tmp, "cache"),
                   env_file=envf, docker=os.path.join(os.path.dirname(__file__), "fake_docker.py"), allow_file_repos=True)
        os.makedirs(cfg["work"])
        self.runner = runnerd.Runner(cfg)
        self.srv = runnerd.ThreadingHTTPServer(("127.0.0.1", 0), runnerd.make_handler(self.runner))
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_address[1]}"

    def tearDown(self):
        self.srv.shutdown()

    def call(self, method, path, obj=None, key=KEY, ts=None, nonce=None):
        body = json.dumps(obj).encode() if obj is not None else b""
        ts = str(ts or int(time.time())); nonce = nonce or uuid.uuid4().hex
        sig = runnerd.sign(key, runnerd.canonical(method, path, ts, nonce, body))
        req = urllib.request.Request(self.base + path, data=body if obj is not None else None, method=method,
                                     headers={"X-X7-Timestamp": ts, "X-X7-Nonce": nonce, "X-X7-Signature": sig, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def spec(self, key="k1", wall=600):
        return {"jobKey": key, "repo": "file://" + self.repo, "baseRef": "main", "cloneToken": "", "mode": "IMPLEMENT", "prompt": "fix add",
                "model": "deepseek-flash", "limits": {"wallClockSec": wall, "maxTurns": 30, "memory": "2g", "cpus": 1.5}}

    def wait(self, jid, t=20):
        end = time.time() + t
        while time.time() < end:
            c, v = self.call("GET", f"/v1/jobs/{jid}")
            if v["state"] not in ("QUEUED", "RUNNING"):
                return v
            time.sleep(0.1)
        self.fail("job 未结束")


class AuthTest(Base):
    def test_known_vector(self):
        c = runnerd.canonical("post", "/v1/jobs", "1700000000", "abc", b'{"a":1}')
        self.assertEqual("413c654cc76b3b2d2c179b0b6a91f56802305d3a1e1ec7c4df493262ae03d4b4", runnerd.sign("k" * 32, c))

    def test_rejects_bad_signature_stale_and_replay(self):
        self.assertEqual(401, self.call("GET", "/v1/jobs/x", key="z" * 40)[0])
        self.assertEqual(401, self.call("GET", "/v1/jobs/x", ts=int(time.time()) - 1000)[0])
        n = uuid.uuid4().hex
        self.assertEqual(404, self.call("GET", "/v1/jobs/x", nonce=n)[0])
        self.assertEqual(401, self.call("GET", "/v1/jobs/x", nonce=n)[0])

    def test_refuses_wildcard_bind(self):
        with self.assertRaises(SystemExit):
            runnerd.Runner(dict(runnerd.CFG, key=KEY, bind="0.0.0.0"))

    def test_validates_input(self):
        s = self.spec(); s["repo"] = "../../etc"
        self.assertEqual(400, self.call("POST", "/v1/jobs", s)[0])
        s = self.spec(); s["repo"] = "../etc"
        self.assertEqual(400, self.call("POST", "/v1/jobs", s)[0])
        s = self.spec(); s["baseRef"] = "--upload-pack=x"
        self.assertEqual(400, self.call("POST", "/v1/jobs", s)[0])
        s = self.spec(); s["limits"]["memory"] = "64g"
        self.assertEqual(400, self.call("POST", "/v1/jobs", s)[0])


class JobTest(Base):
    def test_repair_uses_pinned_pr_commit_and_preserves_previous_changes(self):
        g = ["git", "-C", self.repo]
        subprocess.run(g + ["checkout", "-qb", "agent/issue-42"], check=True)
        with open(os.path.join(self.repo, "math.js"), "a") as f:
            f.write("// previous repair must survive\n")
        with open(os.path.join(self.repo, "previous.js"), "w") as f:
            f.write("export const previous = true;\n")
        subprocess.run(g + ["add", "."], check=True)
        subprocess.run(g + ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "previous repair"], check=True)
        sha = subprocess.check_output(g + ["rev-parse", "HEAD"], text=True).strip()
        subprocess.run(g + ["checkout", "-q", "main"], check=True)

        def repair(job, repo_dir, jobdir, secrets):
            self.assertEqual(sha, subprocess.check_output(["git", "-C", repo_dir, "rev-parse", "HEAD"], text=True).strip())
            path = os.path.join(repo_dir, "math.js")
            with open(path) as f:
                content = f.read()
            with open(path, "w") as f:
                f.write(content.replace("a - b", "a + b"))
            subprocess.run(["git", "-C", repo_dir, "add", "math.js"], check=True)
            subprocess.run(["git", "-C", repo_dir, "commit", "-qm", "second repair"], check=True)
            job.result = {"exitCode": 0}

        self.runner.container = repair
        spec = self.spec("pinned-repair"); spec.update(mode="REPAIR", baseRef=sha)
        code, job = self.call("POST", "/v1/jobs", spec)
        self.assertEqual(200, code)
        result = self.wait(job["id"])
        self.assertEqual("SUCCEEDED", result["state"], result)
        self.assertEqual(sha, result["result"]["baseSha"])
        changes = {f["path"]: f for f in result["result"]["files"]}
        self.assertEqual({"math.js"}, set(changes), "只导出相对本轮 PR 基线的变更")
        self.assertIn("a + b", changes["math.js"]["content"])
        self.assertIn("// previous repair must survive", changes["math.js"]["content"])
        subprocess.run(g + ["checkout", "-q", "agent/issue-42"], check=True)
        with open(os.path.join(self.repo, "math.js"), "w") as f:
            f.write(changes["math.js"]["content"])
        with open(os.path.join(self.repo, "previous.js")) as f:
            self.assertIn("previous = true", f.read())

    def test_success_returns_tracked_changes_only_and_redacts(self):
        os.environ["FAKE_MODE"] = "ok"
        c, v = self.call("POST", "/v1/jobs", self.spec())
        self.assertEqual(200, c)
        self.assertEqual(v["id"], self.call("POST", "/v1/jobs", self.spec())[1]["id"])  # 幂等
        r = self.wait(v["id"])
        self.assertEqual("SUCCEEDED", r["state"], r)
        files = {f["path"]: f for f in r["result"]["files"]}
        self.assertEqual("export const add = (a, b) => a + b;\n", files["math.js"]["content"])
        self.assertTrue(files["old.js"]["delete"])
        self.assertEqual(["scratch.log"], r["result"]["untracked"])
        self.assertEqual(300, r["result"]["usage"]["cacheReadInputTokens"])
        logs = self.call("GET", f"/v1/jobs/{v['id']}/logs?from=0")[1]["events"]
        text = json.dumps(logs)
        self.assertNotIn("sk-abcdefghijklmnopqrstuvwxyz", text)
        self.assertIn("npm test", text)
        self.assertFalse(os.path.exists(os.path.join(self.runner.cfg["work"], v["id"])))  # 工作目录已清理

    def test_one_job_at_a_time_and_cancel(self):
        os.environ["FAKE_MODE"] = "slow"
        _, v = self.call("POST", "/v1/jobs", self.spec("a"))
        time.sleep(0.5)
        c, busy = self.call("POST", "/v1/jobs", self.spec("b"))
        self.assertEqual(409, c); self.assertEqual("BUSY", busy["error"])
        self.assertEqual(200, self.call("POST", f"/v1/jobs/{v['id']}/cancel", {"reason": "user"})[0])
        r = self.wait(v["id"])
        self.assertEqual("CANCELLED", r["state"])
        self.assertTrue(os.path.exists(self.killed))

    def test_timeout(self):
        os.environ["FAKE_MODE"] = "slow"
        self.runner.cfg["work"]  # noqa
        s = self.spec("t", wall=60)
        _, v = self.call("POST", "/v1/jobs", s)
        job = self.runner.jobs[v["id"]]
        time.sleep(0.5); self.runner.cancel(job, "timeout")
        self.assertEqual("TIMED_OUT", self.wait(v["id"])["state"])

    def test_agent_failure(self):
        os.environ["FAKE_MODE"] = "fail"
        _, v = self.call("POST", "/v1/jobs", self.spec("f"))
        r = self.wait(v["id"])
        self.assertEqual("FAILED", r["state"])


class AcceptanceTest(Base):
    def acc(self, key="acc1"):
        return {"jobKey": key, "repo": "file://" + self.repo, "ref": "main", "cloneToken": "", "setup": "npm ci", "start": "npm run dev", "url": "http://127.0.0.1:5173",
                "readySec": 60, "specDir": "e2e/acceptance", "pages": ["/", "/login"], "limits": {"wallClockSec": 600, "maxTurns": 1, "memory": "2560m", "cpus": 1.5}}

    def test_pass_collects_report_shots_a11y_without_model_key(self):
        os.environ["FAKE_ACC"] = "pass"; os.environ["FAKE_ARGS"] = os.path.join(self.tmp, "args.json")
        c, v = self.call("POST", "/v1/acceptance", self.acc())
        self.assertEqual(200, c, v)
        r = self.wait(v["id"])
        self.assertEqual("SUCCEEDED", r["state"], r)
        res = r["result"]
        self.assertEqual(("tests", 1, 0), (res["phase"], res["passed"], res["failed"]))
        self.assertEqual("AC-1 显示登录按钮", res["tests"][0]["title"])
        self.assertEqual(["00-root.png"], [s["name"] for s in res["shots"]])
        self.assertIn("登录", res["a11y"]["00-root"])
        args = json.load(open(os.environ["FAKE_ARGS"]))
        self.assertNotIn("--env-file", args)  # 验收容器不拿模型密钥
        self.assertIn("2560m", args); self.assertIn("x7x24-agent:claude-browser", args)
        self.assertIn("X7_PAGES=/,/login", args)
        del os.environ["FAKE_ARGS"]

    def test_failure_is_reported_and_redacted(self):
        os.environ["FAKE_ACC"] = "fail"
        _, v = self.call("POST", "/v1/acceptance", self.acc("acc2"))
        r = self.wait(v["id"])
        self.assertEqual("SUCCEEDED", r["state"])
        self.assertEqual(1, r["result"]["failed"]); self.assertNotIn("ghp_" + "abcdefghijklmnopqrstuvwxyz0123", json.dumps(r))

    def test_app_not_starting_reports_phase(self):
        os.environ["FAKE_ACC"] = "nostart"
        _, v = self.call("POST", "/v1/acceptance", self.acc("acc3"))
        r = self.wait(v["id"])
        self.assertEqual("start", r["result"]["phase"])

    def test_validates_acceptance(self):
        for k, bad in (("url", "http://203.0.113.1:8787"), ("specDir", "../x"), ("start", ""), ("pages", ["http://evil"]), ("setup", "a\nb")):
            s = self.acc("v" + k); s[k] = bad
            self.assertEqual(400, self.call("POST", "/v1/acceptance", s)[0], k)

    def test_self_test_uses_browser_image_and_mcp(self):
        os.environ["FAKE_MODE"] = "ok"; os.environ["FAKE_ARGS"] = os.path.join(self.tmp, "args2.json")
        s = self.spec("st"); s["selfTest"] = True; s["limits"]["memory"] = "2560m"
        _, v = self.call("POST", "/v1/jobs", s)
        self.assertEqual("SUCCEEDED", self.wait(v["id"])["state"])
        args = json.load(open(os.environ["FAKE_ARGS"]))
        self.assertIn("x7x24-agent:claude-browser", args)
        self.assertTrue(any("--mcp-config /opt/x7/mcp.json" in a for a in args))
        self.assertIn("--env-file", args)
        del os.environ["FAKE_ARGS"]


if __name__ == "__main__":
    unittest.main()


class CloneRetryTest(unittest.TestCase):
    class Job:
        def __init__(self): self.logs = []
        def log(self, kind, msg, *a): self.logs.append((kind, msg))

    def runner(self, results, **cfg):
        r = runnerd.Runner.__new__(runnerd.Runner)
        r.cfg = dict(runnerd.CFG, clone_backoff=0, **cfg); r.calls = []
        def git(args, cwd, env=None, timeout=300):
            r.calls.append(dict(env or {}))
            out = results.pop(0)
            if out == "timeout":
                raise runnerd.subprocess.TimeoutExpired("git", timeout)
            return runnerd.subprocess.CompletedProcess(args, out, "", "fatal: unable to access proxy secret-token")
        r.git = git
        return r

    def test_retries_then_succeeds_without_proxy_env_by_default(self):
        r, job = self.runner([128, "timeout", 0], git_proxy=""), self.Job()
        r.git_clone(job, "https://github.com/o/r.git", "/nonexistent/repo", "/tmp", {"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "x"}, [])
        self.assertEqual(3, len(r.calls)); self.assertEqual(2, sum(1 for k, _ in job.logs if k == "warn"))
        self.assertTrue(all(c["GIT_CONFIG_COUNT"] == "1" for c in r.calls), "未设置 RUNNER_GIT_PROXY 时不覆盖 /etc/gitconfig")

    def test_proxy_env_is_appended_after_auth_header(self):
        r = self.runner([0], git_proxy="socks5h://127.0.0.1:1080")
        r.git_clone(self.Job(), "https://github.com/o/r.git", "/nonexistent/repo", "/tmp", {"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "x"}, [])
        c = r.calls[0]
        self.assertEqual("2", c["GIT_CONFIG_COUNT"]); self.assertEqual("http.https://github.com/.proxy", c["GIT_CONFIG_KEY_1"])
        self.assertEqual("socks5h://127.0.0.1:1080", c["GIT_CONFIG_VALUE_1"])

    def test_gives_up_after_attempts_and_redacts(self):
        r = self.runner([128, 128, 128])
        with self.assertRaises(RuntimeError) as e:
            r.git_clone(self.Job(), "https://github.com/o/r.git", "/nonexistent/repo", "/tmp", {}, ["secret-token"])
        self.assertIn("已重试 3 次", str(e.exception)); self.assertNotIn("secret-token", str(e.exception))
