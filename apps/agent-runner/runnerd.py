#!/usr/bin/env python3
"""x7x24-runnerd：在专用 agent 服务器上，为 x7x24 后端一次跑一个 code agent 容器。

- 只监听私有网卡地址（默认 127.0.0.1:8790），拒绝绑定 0.0.0.0；生产请设 RUNNER_BIND 为内网地址。
- 每个请求必须带 X-X7-Timestamp / X-X7-Nonce / X-X7-Signature（HMAC-SHA256，见 canonical()）。
- 同一时间只跑一个 job；其余请求返回 409。
- clone 在宿主侧完成，GitHub 令牌只通过环境变量传给 git，不写盘、不进容器、不进日志。
- 容器：x7x24-agent:claude，非 root（uid 10001），限内存/CPU/进程数，去掉全部 capability。
- 结束后对比基线：已跟踪文件的改动回传给后端；未跟踪文件只报告路径，不回传。
只用 Python 标准库。
"""
import base64, fcntl, hashlib, hmac, json, os, re, shutil, subprocess, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

OCR_VERSION = "v1.13.5"
OCR_SHA256_LINUX_AMD64 = "f9d918dd8b461719be975430e0f7396b4e642b465ffa490d651ace0d8c989171"
CFG = {
    "bind": os.environ.get("RUNNER_BIND", "127.0.0.1"),
    "port": int(os.environ.get("RUNNER_PORT", "8790")),
    "key": os.environ.get("RUNNER_HMAC_KEY", ""),
    "image": os.environ.get("RUNNER_IMAGE", "x7x24-agent:claude"),
    "browser_image": os.environ.get("RUNNER_BROWSER_IMAGE", "x7x24-agent:claude-browser"),
    "work": os.environ.get("RUNNER_WORK", "/opt/x7x24-agent/jobs"),
    "cache": os.environ.get("RUNNER_CACHE", "/opt/x7x24-agent/cache"),
    "env_file": os.environ.get("RUNNER_ENV_FILE", "/opt/x7x24-agent/model.env"),
    "docker": os.environ.get("RUNNER_DOCKER", "docker"),
    "git": os.environ.get("RUNNER_GIT", "git"),
    "github": os.environ.get("RUNNER_GITHUB", "https://github.com"),
    "git_proxy": os.environ.get("RUNNER_GIT_PROXY", ""),
    "clone_attempts": int(os.environ.get("RUNNER_CLONE_ATTEMPTS", "3")),
    "clone_backoff": float(os.environ.get("RUNNER_CLONE_BACKOFF_SEC", "5")),
    "allow_file_repos": os.environ.get("RUNNER_ALLOW_FILE_REPOS") == "1",  # 仅测试
    "skew": 300,
    "max_file_bytes": 1_000_000,
    "max_files": 300,
    "max_shots": 12,
    "max_shot_bytes": 1_500_000,
    # 逐行审查：alibaba/open-code-review 的 ocr CLI，钉死 v1.13.5，运行前核对 sha256（install-ocr.sh 安装）
    "ocr_bin": os.environ.get("RUNNER_OCR_BIN", "/opt/x7x24-agent/bin/ocr-v1.13.5"),
    "ocr_sha256": os.environ.get("RUNNER_OCR_SHA256", OCR_SHA256_LINUX_AMD64),
    "ocr_work": os.environ.get("RUNNER_OCR_WORK", "/opt/x7x24-agent/ocr"),
    "ocr_keep_days": int(os.environ.get("RUNNER_OCR_KEEP_DAYS", "7")),
    "max_review_comments": 400,
}
URL_RE = re.compile(r"^http://(127\.0\.0\.1|localhost):[0-9]{2,5}(/[A-Za-z0-9_./?=&%-]*)?$")
DIR_RE = re.compile(r"^(?!.*\.\.)[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}$")
PAGE_RE = re.compile(r"^/[A-Za-z0-9_./?=&%#-]{0,199}$")
REPO_RE = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]*/[A-Za-z0-9_-][A-Za-z0-9_.-]*$")
REF_RE = re.compile(r"^[A-Za-z0-9_./-]{1,200}$")
MODEL_RE = re.compile(r"^[A-Za-z0-9_.\[\]-]{1,60}$")
MEM_RE = re.compile(r"^[0-9]{1,5}[mg]$")
SHA_RE = re.compile(r"^[0-9a-f]{7,64}$")
URL_HTTP_RE = re.compile(r"^https?://[A-Za-z0-9.-]+(:[0-9]{2,5})?(/[A-Za-z0-9_./~-]*)?$")
OCR_MODEL_RE = re.compile(r"^[A-Za-z0-9_.:/\[\]-]{1,100}$")
SESSION_RE = re.compile(r"^[0-9a-f-]{8,64}$")
SECRET_PATTERNS = [re.compile(p) for p in (r"sk-[A-Za-z0-9_-]{16,}", r"gh[pousr]_[A-Za-z0-9]{20,}", r"github_pat_[A-Za-z0-9_]{20,}", r"x-access-token:[^@\s]+",
                                           r"AKIA[0-9A-Z]{16}", r"xox[abpr]-[A-Za-z0-9-]{10,}", r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
                                           r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)", r"(?i)(authorization:\s*(bearer|basic)\s+)\S+")]
# 已知的密钥类环境变量名：NAME=value / NAME: value / "NAME": "value" 里的值一律打码（不管值长什么样）
SECRET_ENV_NAMES = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "OPENAI_API_KEY", "DEEPSEEK_API_KEY", "GITHUB_TOKEN", "GH_TOKEN", "RUNNER_HMAC_KEY",
                    "AWS_SECRET_ACCESS_KEY", "AWS_ACCESS_KEY_ID", "DATABASE_URL", "PGPASSWORD", "NPM_TOKEN", "X7_TOKEN", "OCR_LLM_TOKEN")
SECRET_ENV_RE = re.compile(r"""(?i)(\b(?:%s|[A-Z0-9_]*(?:SECRET|PASSWORD|API_KEY|TOKEN)[A-Z0-9_]*)\b["']?\s*[:=]\s*["']?)([^\s"',;]+)""" % "|".join(SECRET_ENV_NAMES))


def mem_mb(v):
    return int(v[:-1]) * (1024 if v.endswith("g") else 1)


def canonical(method, path_query, ts, nonce, body):
    return "\n".join([method.upper(), path_query, ts, nonce, hashlib.sha256(body or b"").hexdigest()])


def sign(key, c):
    return hmac.new(key.encode(), c.encode(), hashlib.sha256).hexdigest()


class Nonces:
    def __init__(self, ttl):
        self.ttl, self.seen, self.lock = ttl, {}, threading.Lock()

    def use(self, nonce, now):
        with self.lock:
            for k in [k for k, t in self.seen.items() if now - t > self.ttl]:
                del self.seen[k]
            if nonce in self.seen:
                return False
            self.seen[nonce] = now
            return True


def env_secrets(path):
    out = []
    try:
        with open(path) as f:
            for line in f:
                if "=" in line:
                    v = line.split("=", 1)[1].strip()
                    if len(v) >= 8:
                        out.append(v)
    except OSError:
        pass
    return out


def redact(text, extra=()):
    for s in extra:
        if s:
            text = text.replace(s, "***")
    text = SECRET_ENV_RE.sub(lambda m: m.group(1) + "***", text)
    for p in SECRET_PATTERNS:
        text = p.sub(lambda m: (m.group(1) + "***") if p.groups and m.group(1) and m.group(1).lower().startswith("authorization") else "***", text)
    return text


def redact_strings(v, extra=()):
    """只对 JSON 里的字符串值脱敏（键名和数字不动，否则 total_tokens 之类会被误伤）。"""
    if isinstance(v, str):
        return redact(v, extra)
    if isinstance(v, list):
        return [redact_strings(x, extra) for x in v]
    if isinstance(v, dict):
        return {k: redact_strings(x, extra) for k, x in v.items()}
    return v


def args_summary(name, inp):
    """工具参数的一句话摘要（不含文件内容 / 补丁正文）。"""
    inp = inp if isinstance(inp, dict) else {}
    for k in ("command", "file_path", "path", "pattern", "url", "query", "selector", "element"):
        v = inp.get(k)
        if isinstance(v, str) and v:
            v = " ".join(v.split())
            return v[:160] + ("…" if len(v) > 160 else "")
    keys = [k for k in inp.keys() if k not in ("content", "new_string", "old_string", "edits")]
    return ",".join(keys[:4])


def result_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(c.get("text", "") if isinstance(c, dict) and c.get("type") == "text" else ("[image]" if isinstance(c, dict) and c.get("type") == "image" else "") for c in content)
    return ""


class ToolTracker:
    """把 stream-json 里的 tool_use / tool_result 配对成精简记录，并合并成批（限流）：
    每批最多 max_items 条或间隔 flush_sec 秒才写一条 kind=tools 日志；连续同名的读类工具合并计数。"""
    READ_TOOLS = ("Read", "Glob", "Grep", "LS")

    def __init__(self, job, secrets, clock=time.monotonic, flush_sec=3.0, max_items=12, out_max=400):
        self.job, self.secrets, self.clock = job, secrets, clock
        self.flush_sec, self.max_items, self.out_max = flush_sec, max_items, out_max
        self.open, self.batch, self.last_flush, self.dropped = {}, [], clock(), 0

    def use(self, part):
        name = str(part.get("name") or "?")
        self.open[part.get("id")] = {"name": name, "args": redact(args_summary(name, part.get("input")), self.secrets), "t0": self.clock(),
                                     "browser": name.startswith("mcp__playwright")}

    def result(self, part):
        u = self.open.pop(part.get("tool_use_id"), None)
        if u is None:
            return
        out = redact(result_text(part.get("content")), self.secrets).strip()
        item = {"name": u["name"], "args": u["args"], "ms": int((self.clock() - u["t0"]) * 1000), "ok": not part.get("is_error"),
                "out": (out[-self.out_max:] if len(out) > self.out_max else out)}
        if len(out) > self.out_max:
            item["truncated"] = True
        if u["browser"]:
            item["browser"] = True
        last = self.batch[-1] if self.batch else None
        if last and item["ok"] and last.get("ok") and item["name"] in self.READ_TOOLS and last["name"] == item["name"]:
            last["count"] = last.get("count", 1) + 1; last["ms"] += item["ms"]; last["args"] = item["args"]; last["out"] = ""
        elif len(self.batch) >= 50:
            self.dropped += 1
        else:
            self.batch.append(item)
        if len(self.batch) >= self.max_items or self.clock() - self.last_flush >= self.flush_sec or item.get("browser") or not item["ok"]:
            self.flush()

    def flush(self, final=False):
        if final:  # 没等到结果的调用（被中断 / 超时）也要记下来
            for u in self.open.values():
                self.batch.append({"name": u["name"], "args": u["args"], "ms": int((self.clock() - u["t0"]) * 1000), "ok": None, "out": ""})
            self.open = {}
        if self.batch:
            body = {"items": self.batch}
            if self.dropped:
                body["dropped"] = self.dropped
            self.job.log("tools", json.dumps(body, ensure_ascii=False), self.secrets, limit=8000)
        self.batch, self.dropped, self.last_flush = [], 0, self.clock()


class Job:
    def __init__(self, spec, kind="code"):
        self.kind = kind
        self.id = "job_" + uuid.uuid4().hex[:20]
        self.key = spec["jobKey"]
        self.spec = spec
        self.state, self.error, self.result = "QUEUED", None, None
        self.logs, self.lock = [], threading.Lock()
        self.proc, self.cancel_reason, self.container = None, None, None
        self.started = time.time()
        self.reported_model = None

    def log(self, kind, text, secrets=(), limit=4000):
        with self.lock:
            self.logs.append({"seq": len(self.logs), "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "kind": kind, "text": redact(str(text), secrets)[:limit]})

    def view(self):
        state, result = self.state, self.result
        if state not in ("QUEUED", "RUNNING") and self.reported_model:
            result = {**(result or {}), "observedModel": self.reported_model}
        return {"id": self.id, "jobKey": self.key, "state": state, "error": self.error, "result": result}


class Runner:
    def __init__(self, cfg):
        if len(cfg["key"]) < 32:
            raise SystemExit("RUNNER_HMAC_KEY 至少 32 字符")
        if cfg["bind"] in ("0.0.0.0", "::", ""):
            raise SystemExit("拒绝监听所有地址：请设置 RUNNER_BIND 为私有网卡地址（不要用 0.0.0.0）")
        self.cfg, self.jobs, self.by_key, self.active = cfg, {}, {}, None
        self.lock, self.nonces = threading.Lock(), Nonces(cfg["skew"] * 2)

    # ── 鉴权
    def verify(self, method, path_query, headers, body):
        ts, nonce, sig = headers.get("X-X7-Timestamp", ""), headers.get("X-X7-Nonce", ""), headers.get("X-X7-Signature", "")
        if not ts.isdigit() or not re.fullmatch(r"[0-9a-f]{16,64}", nonce) or not re.fullmatch(r"[0-9a-f]{64}", sig):
            return False
        now = int(time.time())
        if abs(now - int(ts)) > self.cfg["skew"]:
            return False
        if not hmac.compare_digest(sign(self.cfg["key"], canonical(method, path_query, ts, nonce, body)), sig):
            return False
        return self.nonces.use(nonce, now)

    # ── job 管理
    def submit(self, spec, kind="code"):
        err = {"code": self.validate, "acceptance": self.validate_acceptance, "review": self.validate_review}[kind](spec)
        if err:
            return 400, {"error": err}
        with self.lock:
            if spec["jobKey"] in self.by_key:
                return 200, self.by_key[spec["jobKey"]].view()
            if self.active and self.active.state in ("QUEUED", "RUNNING"):
                return 409, {"error": "BUSY", "activeJobKey": self.active.key}
            job = Job(spec, kind)
            self.jobs[job.id], self.by_key[job.key], self.active = job, job, job
            for old in sorted(self.jobs.values(), key=lambda j: j.started)[:-20]:
                self.jobs.pop(old.id, None); self.by_key.pop(old.key, None)
        target = {"code": self.run, "acceptance": self.run_acceptance, "review": self.run_review}[kind]
        threading.Thread(target=target, args=(job,), daemon=True).start()
        return 200, job.view()

    def validate_limits(self, lim):
        try:
            if not (60 <= int(lim.get("wallClockSec", 0)) <= 7200 and 1 <= int(lim.get("maxTurns", 0)) <= 200 and 0.25 <= float(lim.get("cpus", 0)) <= 2 and MEM_RE.match(str(lim.get("memory", ""))) and mem_mb(lim["memory"]) <= 3072):
                return "limits 超出范围"
        except (TypeError, ValueError):
            return "limits 无效"
        return None

    def validate_acceptance(self, s):
        if not isinstance(s, dict) or not re.fullmatch(r"[A-Za-z0-9_:.-]{1,200}", str(s.get("jobKey", ""))):
            return "jobKey 无效"
        repo = str(s.get("repo", ""))
        if not (REPO_RE.match(repo) or (self.cfg["allow_file_repos"] and repo.startswith("file://"))):
            return "repo 无效"
        if not REF_RE.match(str(s.get("ref", ""))) or str(s.get("ref")).startswith("-"):
            return "ref 无效"
        for k in ("setup", "start"):
            if not isinstance(s.get(k, ""), str) or len(s.get(k, "")) > 500 or "\n" in s.get(k, ""):
                return k + " 无效"
        if not s.get("start"):
            return "缺少 start"
        if not URL_RE.match(str(s.get("url", ""))):
            return "url 只能是容器内 http://127.0.0.1:端口"
        if not 10 <= int(s.get("readySec", 0) or 0) <= 600:
            return "readySec 超出范围"
        if s.get("specDir") and not DIR_RE.match(str(s["specDir"])):
            return "specDir 无效"
        pages = s.get("pages") or ["/"]
        if not isinstance(pages, list) or len(pages) > self.cfg["max_shots"] or not all(isinstance(p, str) and PAGE_RE.match(p) for p in pages):
            return "pages 无效"
        return self.validate_limits(s.get("limits") or {})

    def validate(self, s):
        if not isinstance(s, dict) or not re.fullmatch(r"[A-Za-z0-9_:.-]{1,200}", str(s.get("jobKey", ""))):
            return "jobKey 无效"
        repo = str(s.get("repo", ""))
        if not (REPO_RE.match(repo) or (self.cfg["allow_file_repos"] and repo.startswith("file://"))):
            return "repo 无效"
        if not REF_RE.match(str(s.get("baseRef", ""))) or str(s.get("baseRef")).startswith("-"):
            return "baseRef 无效"
        if not MODEL_RE.match(str(s.get("model", ""))):
            return "model 无效"
        if not isinstance(s.get("selfTest", False), bool):
            return "selfTest 无效"
        if s.get("mode") not in ("IMPLEMENT", "REPAIR") or not isinstance(s.get("prompt"), str) or not s["prompt"].strip():
            return "mode/prompt 无效"
        return self.validate_limits(s.get("limits") or {})

    def validate_review(self, s):
        if not isinstance(s, dict) or not re.fullmatch(r"[A-Za-z0-9_:.-]{1,200}", str(s.get("jobKey", ""))):
            return "jobKey 无效"
        if not re.fullmatch(r"[A-Za-z0-9_:.-]{1,200}", str(s.get("reviewKey", ""))):
            return "reviewKey 无效"
        repo = str(s.get("repo", ""))
        if not (REPO_RE.match(repo) or (self.cfg["allow_file_repos"] and repo.startswith("file://"))):
            return "repo 无效"
        base = str(s.get("baseRef", ""))
        if not REF_RE.match(base) or base.startswith("-"):
            return "baseRef 无效"
        if not SHA_RE.match(str(s.get("headSha", ""))):
            return "headSha 无效"
        llm = s.get("llm")
        if not isinstance(llm, dict) or not URL_HTTP_RE.match(str(llm.get("url", ""))) or not OCR_MODEL_RE.match(str(llm.get("model", ""))):
            return "llm 无效"
        if not isinstance(llm.get("token"), str) or not llm["token"] or len(llm["token"]) > 500 or "\n" in llm["token"]:
            return "llm.token 无效"
        if llm.get("protocol", "openai") not in ("openai", "anthropic"):
            return "llm.protocol 无效"
        if s.get("resume") and not SESSION_RE.match(str(s["resume"])):
            return "resume 无效"
        if not isinstance(s.get("background", ""), str) or len(s.get("background", "")) > 6000:
            return "background 无效"
        try:
            if not (0 <= int(s.get("maxTokensBudget", 0)) <= 50_000_000 and 1 <= int(s.get("timeoutMin", 15)) <= 60 and 1 <= int(s.get("concurrency", 8)) <= 16):
                return "审查参数超出范围"
        except (TypeError, ValueError):
            return "审查参数无效"
        return self.validate_limits(s.get("limits") or {})

    def cancel(self, job, reason):
        job.cancel_reason = reason or "cancelled"
        self.kill(job)

    def kill(self, job):
        if job.container and job.kind == "review":
            # ocr 收到 SIGINT 会把已审完的文件写进会话并输出结果，之后可用 --resume 接着审
            subprocess.run([self.cfg["docker"], "kill", "--signal", "INT", job.container], capture_output=True, timeout=30)
            if job.proc:
                try:
                    job.proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    pass
            if job.proc and job.proc.poll() is None:
                subprocess.run([self.cfg["docker"], "kill", job.container], capture_output=True, timeout=30)
            return
        if job.container:
            subprocess.run([self.cfg["docker"], "kill", job.container], capture_output=True, timeout=30)
        if job.proc and job.proc.poll() is None:
            job.proc.kill()

    def git(self, args, cwd, env=None, timeout=300):
        return subprocess.run([self.cfg["git"], *args], cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)

    def git_clone(self, job, url, repo_dir, jobdir, env, secrets):
        """clone 带重试；RUNNER_GIT_PROXY 只在设置时追加，不设置时沿用系统 /etc/gitconfig 的代理。"""
        cfg, env = self.cfg, dict(env)
        proxy = cfg.get("git_proxy") or ""
        if proxy and not url.startswith("file://"):
            n = int(env.get("GIT_CONFIG_COUNT", "0"))
            env.update({"GIT_CONFIG_COUNT": str(n + 1), f"GIT_CONFIG_KEY_{n}": f"http.{cfg['github']}/.proxy", f"GIT_CONFIG_VALUE_{n}": proxy})
        attempts = max(1, int(cfg.get("clone_attempts", 3))); err = ""
        for i in range(attempts):
            if i:
                shutil.rmtree(repo_dir, ignore_errors=True)
                wait = float(cfg.get("clone_backoff", 5)) * (3 ** (i - 1))
                job.log("warn", f"clone 第 {i} 次失败，{wait:g} 秒后重试（共 {attempts} 次）：{err[-200:]}")
                time.sleep(wait)
            try:
                r = self.git(["clone", "--quiet", "--no-checkout", url, repo_dir], jobdir, env)
                if r.returncode == 0:
                    return
                err = redact(r.stderr[-500:], secrets + ([proxy] if proxy else []))
            except subprocess.TimeoutExpired:
                err = "git clone 超时"
        raise RuntimeError(f"clone 失败（已重试 {attempts} 次）：" + err)

    def run(self, job):
        s, cfg = job.spec, self.cfg
        token = s.get("cloneToken") or ""
        secrets = [token] + env_secrets(cfg["env_file"])
        jobdir = os.path.join(cfg["work"], job.id); repo_dir = os.path.join(jobdir, "repo")
        try:
            job.state = "RUNNING"
            os.makedirs(jobdir, mode=0o700)
            url = s["repo"] if s["repo"].startswith("file://") else f"{cfg['github']}/{s['repo']}.git"
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": jobdir, "GIT_TERMINAL_PROMPT": "0"}
            if token:
                basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
                env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": f"http.{cfg['github']}/.extraheader", "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}"})
            job.log("system", f"clone {s['repo']} @ {s['baseRef']}")
            self.git_clone(job, url, repo_dir, jobdir, env, secrets)
            del env
            r = self.git(["checkout", "--quiet", "--detach", s["baseRef"]], repo_dir)
            if r.returncode != 0:
                r = self.git(["checkout", "--quiet", "--detach", "origin/" + s["baseRef"]], repo_dir)
            if r.returncode != 0:
                raise RuntimeError("找不到基线 " + s["baseRef"])
            base = self.git(["rev-parse", "HEAD"], repo_dir).stdout.strip()
            self.git(["config", "user.name", "x7x24-agent"], repo_dir); self.git(["config", "user.email", "agent@x7x24.invalid"], repo_dir)
            with open(os.path.join(jobdir, "prompt.md"), "w") as f:
                f.write(s["prompt"])
            if job.cancel_reason:
                raise InterruptedError(job.cancel_reason)
            self.container(job, repo_dir, jobdir, secrets)
            if job.cancel_reason:
                job.state, job.error = ("TIMED_OUT" if job.cancel_reason == "timeout" else "CANCELLED"), job.cancel_reason
                return
            files, untracked = self.collect(repo_dir, base)
            if untracked:
                job.log("warn", "未跟踪文件未回传：" + ", ".join(untracked[:50]))
            job.result = {**(job.result or {}), "files": files, "untracked": untracked, "baseSha": base,
                          "headSha": self.git(["rev-parse", "HEAD"], repo_dir).stdout.strip()}
            job.state = "SUCCEEDED" if job.result.get("exitCode", 1) == 0 else "FAILED"
            if job.state == "FAILED":
                if job.result.get("agentSubtype") == "error_max_turns":
                    job.error = "agent 用完轮数上限（%s 轮，error_max_turns）" % job.result.get("numTurns")
                else:
                    job.error = "agent 退出码 %s" % job.result.get("exitCode")
        except InterruptedError as e:
            job.state, job.error = "CANCELLED", str(e)
        except Exception as e:  # noqa: BLE001
            job.state, job.error = "FAILED", redact(str(e), secrets)[:1000]
        finally:
            job.log("system", f"结束：{job.state}")
            shutil.rmtree(jobdir, ignore_errors=True)
            token = None

    def container(self, job, repo_dir, jobdir, secrets):
        s, cfg, lim = job.spec, self.cfg, job.spec["limits"]
        job.container = "x7job-" + job.id[4:]
        cache = cfg["cache"]
        for d in ("m2", "npm", "gradle"):
            os.makedirs(os.path.join(cache, d), exist_ok=True)
        self_test = bool(s.get("selfTest"))
        image = cfg["browser_image"] if self_test else cfg["image"]
        mcp = " --mcp-config /opt/x7/mcp.json --allowedTools mcp__playwright" if self_test else ""
        cmd = [cfg["docker"], "run", "--rm", "--name", job.container, "--user", "10001:10001", "--shm-size", "256m",
               "--memory", lim["memory"], "--memory-swap", lim["memory"], "--cpus", str(lim["cpus"]), "--pids-limit", "512",
               "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--env-file", cfg["env_file"],
               "-e", "ANTHROPIC_MODEL=" + s["model"], "-e", "ANTHROPIC_SMALL_FAST_MODEL=" + s["model"],
               "-e", "MAVEN_OPTS=-Xmx768m",
               "-v", f"{repo_dir}:/work", "-v", f"{jobdir}/prompt.md:/job/prompt.md:ro",
               "-v", f"{cache}/m2:/home/agent/.m2", "-v", f"{cache}/npm:/home/agent/.npm", "-v", f"{cache}/gradle:/home/agent/.gradle",
               image, "sh", "-c",
               'claude --bare -p "$(cat /job/prompt.md)" --output-format stream-json --verbose --max-turns "$1" '
               '--dangerously-skip-permissions --disallowedTools WebSearch,WebFetch' + mcp, "agent", str(int(lim["maxTurns"]))]
        timer = threading.Timer(int(lim["wallClockSec"]), lambda: self.cancel(job, "timeout"))
        lockf = open(os.path.join(cache, ".lock"), "w")
        fcntl.flock(lockf, fcntl.LOCK_EX)  # 共享依赖缓存：同一时间只允许一个 job 写入
        try:
            job.log("system", f"启动容器 {image}（{lim['memory']} / {lim['cpus']} CPU / {lim['maxTurns']} 轮）")
            timer.start()
            job.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            job.tools = ToolTracker(job, secrets)
            for line in job.proc.stdout:
                self.event(job, line, secrets)
            job.tools.flush(final=True)
            code = job.proc.wait()
            job.result = {**(job.result or {}), "exitCode": code}
        finally:
            timer.cancel(); fcntl.flock(lockf, fcntl.LOCK_UN); lockf.close()

    def clone(self, job, url_repo, ref, token, jobdir, secrets):
        cfg = self.cfg; repo_dir = os.path.join(jobdir, "repo")
        url = url_repo if url_repo.startswith("file://") else f"{cfg['github']}/{url_repo}.git"
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": jobdir, "GIT_TERMINAL_PROMPT": "0"}
        if token:
            basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
            env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": f"http.{cfg['github']}/.extraheader", "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}"})
        job.log("system", f"clone {url_repo} @ {ref}")
        self.git_clone(job, url, repo_dir, jobdir, env, secrets)
        del env
        for cand in (ref, "origin/" + ref):
            if self.git(["checkout", "--quiet", "--detach", cand], repo_dir).returncode == 0:
                return repo_dir
        raise RuntimeError("找不到分支或提交 " + ref)

    # ── 逐行审查（open-code-review 官方 skill：ocr 自己调模型）
    def ocr_ready(self):
        """钉死版本：二进制必须存在且 sha256 与 OCR_SHA256 一致，否则不跑（不在运行时下载）。"""
        path = self.cfg["ocr_bin"]
        if not os.path.isfile(path):
            raise RuntimeError(f"没装 ocr {OCR_VERSION}：先在 runner 上执行 install-ocr.sh（{path} 不存在）")
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        if self.cfg["ocr_sha256"] and h.hexdigest() != self.cfg["ocr_sha256"]:
            raise RuntimeError(f"ocr 二进制校验和不对（应为 {OCR_VERSION}），拒绝运行")
        return path

    def review_dirs(self, key):
        """同一 reviewKey（任务 + 节点）复用同一个仓库目录和 HOME：ocr 的会话按仓库路径存在 HOME 下，--resume 才找得到。"""
        root = self.cfg["ocr_work"]
        os.makedirs(root, mode=0o700, exist_ok=True)
        keep = self.cfg["ocr_keep_days"] * 86400; now = time.time()
        for name in os.listdir(root):
            d = os.path.join(root, name)
            try:
                if os.path.isdir(d) and not os.path.islink(d) and now - os.path.getmtime(d) > keep:
                    shutil.rmtree(d, ignore_errors=True)
            except OSError:
                pass
        d = os.path.join(root, hashlib.sha256(key.encode()).hexdigest()[:24])
        for sub in ("", "home", "out"):
            os.makedirs(os.path.join(d, sub), mode=0o700, exist_ok=True)
        os.utime(d)
        return d

    def prepare_review_repo(self, job, d, s, token, secrets):
        repo_dir = os.path.join(d, "repo")
        if os.path.isdir(os.path.join(repo_dir, ".git")):
            url = s["repo"] if s["repo"].startswith("file://") else f"{self.cfg['github']}/{s['repo']}.git"
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": d, "GIT_TERMINAL_PROMPT": "0"}
            if token:
                basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
                env.update({"GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": f"http.{self.cfg['github']}/.extraheader", "GIT_CONFIG_VALUE_0": f"AUTHORIZATION: basic {basic}"})
            proxy = self.cfg.get("git_proxy") or ""
            if proxy and not url.startswith("file://"):
                n = int(env.get("GIT_CONFIG_COUNT", "0"))
                env.update({"GIT_CONFIG_COUNT": str(n + 1), f"GIT_CONFIG_KEY_{n}": f"http.{self.cfg['github']}/.proxy", f"GIT_CONFIG_VALUE_{n}": proxy})
            job.log("system", f"复用审查目录，更新 {s['repo']}")
            r = self.git(["fetch", "--quiet", "--prune", url, "+refs/heads/*:refs/remotes/origin/*"], repo_dir, env)
            del env
            if r.returncode != 0:
                shutil.rmtree(repo_dir, ignore_errors=True)
                job.log("warn", "更新失败，重新 clone：" + redact(r.stderr[-300:], secrets))
        if not os.path.isdir(os.path.join(repo_dir, ".git")):
            # clone() 放在 d/repo（路径固定，--resume 依赖它）
            self.clone(job, s["repo"], s["baseRef"], token, d, secrets)
        head = s["headSha"]
        if self.git(["checkout", "--quiet", "--detach", head], repo_dir).returncode != 0:
            raise RuntimeError("找不到 PR 头提交 " + head)
        return repo_dir

    def run_review(self, job):
        """按官方 open-code-review skill 的命令跑：ocr review --audience agent -b <背景> --from <base> --to <head> --format json --output <文件>。
        模型密钥只经环境变量交给 docker / ocr，不写盘、不进命令行、不进日志。"""
        s, cfg, lim = job.spec, self.cfg, job.spec["limits"]
        token = s.get("cloneToken") or ""
        llm_token = s["llm"]["token"]
        secrets = [token, llm_token]
        try:
            job.state = "RUNNING"
            ocr = self.ocr_ready()
            d = self.review_dirs(s["reviewKey"])
            out_file = os.path.join(d, "out", job.id + ".json")
            repo_dir = self.prepare_review_repo(job, d, s, token, secrets)
            token = None
            if job.cancel_reason:
                raise InterruptedError(job.cancel_reason)
            base = "origin/" + s["baseRef"]  # 与 PR 目标分支比较（ocr 按 merge-base 取改动）
            args = ["review", "--audience", "agent", "--format", "json", "--output", "/out/" + job.id + ".json",
                    "--from", base, "--to", s["headSha"], "--timeout", str(int(s.get("timeoutMin", 15))), "--concurrency", str(int(s.get("concurrency", 8)))]
            if s.get("background"):
                args += ["--background", s["background"]]
            if int(s.get("maxTokensBudget", 0) or 0) > 0:
                args += ["--max-tokens-budget", str(int(s["maxTokensBudget"]))]
            if s.get("resume"):
                args += ["--resume", s["resume"]]
            job.container = "x7ocr-" + job.id[4:]
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "OCR_LLM_URL": s["llm"]["url"], "OCR_LLM_TOKEN": llm_token,
                   "OCR_LLM_MODEL": s["llm"]["model"], "OCR_LLM_PROTOCOL": s["llm"].get("protocol", "openai")}
            for k in ("DOCKER_HOST", "DOCKER_CONFIG"):
                if os.environ.get(k):
                    env[k] = os.environ[k]
            cmd = [cfg["docker"], "run", "--rm", "--name", job.container, "--user", "10001:10001",
                   "--memory", lim["memory"], "--memory-swap", lim["memory"], "--cpus", str(lim["cpus"]), "--pids-limit", "512",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                   # 只传变量名：值从 docker CLI 进程的环境里取，不出现在命令行
                   "-e", "OCR_LLM_URL", "-e", "OCR_LLM_TOKEN", "-e", "OCR_LLM_MODEL", "-e", "OCR_LLM_PROTOCOL",
                   "-e", "HOME=/home/ocr", "-e", "GIT_CONFIG_COUNT=1", "-e", "GIT_CONFIG_KEY_0=safe.directory", "-e", "GIT_CONFIG_VALUE_0=*",
                   "-v", f"{repo_dir}:/work", "-v", f"{d}/home:/home/ocr", "-v", f"{d}/out:/out", "-v", f"{ocr}:/opt/x7/ocr:ro",
                   "-w", "/work", cfg["image"], "/opt/x7/ocr", *args]
            timer = threading.Timer(int(lim["wallClockSec"]), lambda: self.cancel(job, "timeout"))
            job.log("system", f"开始逐行审查：ocr {OCR_VERSION}，模型 {s['llm']['model']}" + ("，接着上次会话" if s.get("resume") else ""))
            stderr = []
            try:
                timer.start()
                job.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)
                del env
                for line in job.proc.stdout:
                    line = line.rstrip()
                    if line:
                        stderr.append(redact(line, secrets)[:2000]); del stderr[:-200]
                        if line.startswith("[ocr]") or line.startswith("Error"):
                            job.log("ocr", line, secrets)
                code = job.proc.wait()
            finally:
                timer.cancel()
            result = self.collect_review(out_file, stderr, code, secrets)
            job.result = result
            if job.cancel_reason:
                job.state, job.error = ("TIMED_OUT" if job.cancel_reason == "timeout" else "CANCELLED"), job.cancel_reason
                return
            job.state = "SUCCEEDED" if result.get("ocr") is not None else "FAILED"
            if job.state == "FAILED":
                job.error = f"ocr 退出码 {code}，没有输出结果"
        except InterruptedError as e:
            job.state, job.error = "CANCELLED", str(e)
        except Exception as e:  # noqa: BLE001
            job.state, job.error = "FAILED", redact(str(e), secrets)[:1000]
        finally:
            job.log("system", f"结束：{job.state}")
            llm_token = None; token = None
            job.spec = {k: v for k, v in job.spec.items() if k not in ("llm", "cloneToken")}

    def collect_review(self, out_file, lines, code, secrets):
        """读 ocr 的 JSON 结果；没写出文件时（启动就失败）从输出里找 stderr 的失败记录。去掉 thinking 等大字段，限制条数。"""
        doc = None
        try:
            with open(out_file, encoding="utf-8") as f:
                doc = json.load(f)
        except (OSError, ValueError):
            text = "\n".join(lines)
            i = text.find("{")
            while i >= 0 and doc is None:
                try:
                    doc, _ = json.JSONDecoder().raw_decode(text[i:])
                except ValueError:
                    i = text.find("{", i + 1)
        finally:
            try:
                os.remove(out_file)
            except OSError:
                pass
        if isinstance(doc, dict):
            cs = doc.get("comments") or []
            doc["comments"] = [{k: v for k, v in c.items() if k != "thinking"} for c in cs[: self.cfg["max_review_comments"]] if isinstance(c, dict)]
            if len(cs) > self.cfg["max_review_comments"]:
                doc["truncatedComments"] = len(cs) - self.cfg["max_review_comments"]
            rr = doc.pop("retry_report", None)
            if isinstance(rr, dict):
                doc["retry"] = {k: rr.get(k) for k in ("total_requests", "retried_requests", "total_retries", "failed_requests")}
            doc = redact_strings(doc, secrets)
        else:
            doc = None
        return {"exitCode": code, "ocrVersion": OCR_VERSION, "ocr": doc, "outputTail": "\n".join(lines[-20:])}

    def run_acceptance(self, job):
        """独立验收：干净容器（不注入任何模型密钥）从 PR 分支起应用，跑验收脚本，截关键页面。"""
        s, cfg, lim = job.spec, self.cfg, job.spec["limits"]
        token = s.get("cloneToken") or ""
        secrets = [token] + env_secrets(cfg["env_file"])
        jobdir = os.path.join(cfg["work"], job.id); out = os.path.join(jobdir, "out")
        try:
            job.state = "RUNNING"
            os.makedirs(out, mode=0o700)
            repo_dir = self.clone(job, s["repo"], s["ref"], token, jobdir, secrets)
            token = None
            if job.cancel_reason:
                raise InterruptedError(job.cancel_reason)
            job.container = "x7acc-" + job.id[4:]
            cache = cfg["cache"]
            for d in ("m2", "npm", "gradle"):
                os.makedirs(os.path.join(cache, d), exist_ok=True)
            envs = {"X7_SETUP": s.get("setup", ""), "X7_START": s["start"], "X7_URL": s["url"], "X7_READY_SEC": str(int(s["readySec"])),
                    "X7_SPEC_DIR": s.get("specDir", ""), "X7_PAGES": ",".join(s.get("pages") or ["/"])}
            cmd = [cfg["docker"], "run", "--rm", "--name", job.container, "--user", "10001:10001", "--shm-size", "256m",
                   "--memory", lim["memory"], "--memory-swap", lim["memory"], "--cpus", str(lim["cpus"]), "--pids-limit", "1024",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges"]
            for k, v in envs.items():
                cmd += ["-e", f"{k}={v}"]
            cmd += ["-v", f"{repo_dir}:/work", "-v", f"{out}:/out",
                    "-v", f"{cache}/m2:/home/agent/.m2", "-v", f"{cache}/npm:/home/agent/.npm", "-v", f"{cache}/gradle:/home/agent/.gradle",
                    cfg["browser_image"], "/opt/x7/acceptance.sh"]
            timer = threading.Timer(int(lim["wallClockSec"]), lambda: self.cancel(job, "timeout"))
            lockf = open(os.path.join(cache, ".lock"), "w"); fcntl.flock(lockf, fcntl.LOCK_EX)
            try:
                job.log("system", f"启动验收容器 {cfg['browser_image']}（{lim['memory']} / {lim['cpus']} CPU）")
                timer.start()
                job.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                for line in job.proc.stdout:
                    if line.strip():
                        job.log("acceptance", line.rstrip(), secrets)
                code = job.proc.wait()
            finally:
                timer.cancel(); fcntl.flock(lockf, fcntl.LOCK_UN); lockf.close()
            if job.cancel_reason:
                job.state, job.error = ("TIMED_OUT" if job.cancel_reason == "timeout" else "CANCELLED"), job.cancel_reason
                return
            job.result = self.collect_acceptance(out, code, secrets)
            job.state = "SUCCEEDED" if code in (0, 3, 4) else "FAILED"
            if job.state == "FAILED":
                job.error = f"验收容器退出码 {code}"
        except InterruptedError as e:
            job.state, job.error = "CANCELLED", str(e)
        except Exception as e:  # noqa: BLE001
            job.state, job.error = "FAILED", redact(str(e), secrets)[:1000]
        finally:
            job.log("system", f"结束：{job.state}")
            shutil.rmtree(jobdir, ignore_errors=True)

    def collect_acceptance(self, out, code, secrets):
        phase = {3: "setup", 4: "start"}.get(code, "tests")
        try:
            with open(os.path.join(out, "status.json")) as f:
                phase = json.load(f).get("phase", phase)
        except (OSError, ValueError):
            pass
        tests, passed, failed = [], 0, 0
        try:
            with open(os.path.join(out, "report.json")) as f:
                rep = json.load(f)
            st = rep.get("stats") or {}
            passed, failed = int(st.get("expected", 0)), int(st.get("unexpected", 0)) + int(st.get("flaky", 0))

            def walk(suite, prefix):
                for sp in suite.get("specs", []):
                    for t in sp.get("tests", []):
                        res = (t.get("results") or [{}])[-1]
                        err = (res.get("error") or {}).get("message")
                        tests.append({"title": (prefix + " " + sp.get("title", "")).strip(), "status": res.get("status", t.get("status", "unknown")),
                                      "error": redact(err, secrets)[:1000] if err else None})
                for sub in suite.get("suites", []):
                    walk(sub, (prefix + " " + sub.get("title", "")).strip() if not sub.get("file") else prefix)
            for su in rep.get("suites", []):
                walk(su, "")
        except (OSError, ValueError):
            pass
        shots, a11y = [], {}
        sd = os.path.join(out, "shots")
        for name in sorted(os.listdir(sd))[: self.cfg["max_shots"]] if os.path.isdir(sd) else []:
            p = os.path.join(sd, name)
            if name.endswith(".png") and not os.path.islink(p) and os.path.getsize(p) <= self.cfg["max_shot_bytes"]:
                with open(p, "rb") as f:
                    shots.append({"name": name, "pngBase64": base64.b64encode(f.read()).decode()})
        ad = os.path.join(out, "a11y")
        for name in sorted(os.listdir(ad))[: self.cfg["max_shots"]] if os.path.isdir(ad) else []:
            with open(os.path.join(ad, name), errors="replace") as f:
                a11y[name[:-4] if name.endswith(".txt") else name] = redact(f.read(8000), secrets)
        tail = ""
        try:
            with open(os.path.join(out, "app.log"), errors="replace") as f:
                tail = redact(f.read()[-2000:], secrets)
        except OSError:
            pass
        return {"phase": phase, "exitCode": code, "tests": tests, "passed": passed, "failed": failed, "shots": shots, "a11y": a11y, "appLogTail": tail}

    def event(self, job, line, secrets):
        line = line.strip()
        if not line:
            return
        try:
            e = json.loads(line)
        except ValueError:
            job.log("stderr", line, secrets); return
        t = e.get("type"); tools = getattr(job, "tools", None) or ToolTracker(job, secrets)
        job.tools = tools
        model = e.get("model") if t == "system" and e.get("subtype") == "init" else (e.get("message") or {}).get("model") if t == "assistant" else None
        if isinstance(model, str):
            observed = redact(model, secrets).strip()[:512]
            if observed and observed != job.reported_model:
                job.reported_model = observed
                job.log("model", json.dumps({"model": observed}, ensure_ascii=False))
        if t == "assistant":
            for part in (e.get("message") or {}).get("content") or []:
                if part.get("type") == "text" and part.get("text"):
                    job.log("text", part["text"], secrets)
                elif part.get("type") == "tool_use":
                    tools.use(part)
        elif t == "user":
            for part in (e.get("message") or {}).get("content") or []:
                if isinstance(part, dict) and part.get("type") == "tool_result":
                    tools.result(part)
        elif t == "result":
            tools.flush(final=True)
            u = e.get("usage") or {}
            job.result = {**(job.result or {}), "summary": redact(str(e.get("result", "")), secrets)[:4000], "numTurns": e.get("num_turns", 0),
                          "usage": {"inputTokens": u.get("input_tokens", 0), "outputTokens": u.get("output_tokens", 0), "cacheReadInputTokens": u.get("cache_read_input_tokens", 0)},
                          "agentReportedCostUsd": e.get("total_cost_usd"), "agentSubtype": e.get("subtype")}
            job.log("result", f"{e.get('subtype')}，{e.get('num_turns')} 轮", secrets)

    def collect(self, repo_dir, base):
        st = self.git(["status", "--porcelain", "--untracked-files=all"], repo_dir).stdout.splitlines()
        untracked = [l[3:] for l in st if l.startswith("?? ")]
        diff = self.git(["diff", "--name-status", "--no-renames", base], repo_dir).stdout.splitlines()
        files = []
        for row in diff[: self.cfg["max_files"]]:
            status, path = row.split("\t", 1)
            if status.startswith("D"):
                files.append({"path": path, "delete": True}); continue
            full = os.path.join(repo_dir, path)
            if os.path.islink(full) or not os.path.isfile(full) or os.path.getsize(full) > self.cfg["max_file_bytes"]:
                untracked.append(path + "（过大或非普通文件，未回传）"); continue
            try:
                with open(full, encoding="utf-8") as f:
                    files.append({"path": path, "content": f.read(), "delete": False})
            except UnicodeDecodeError:
                untracked.append(path + "（二进制，未回传）")
        return files, untracked


def make_handler(runner):
    class H(BaseHTTPRequestHandler):
        server_version = "x7x24-runnerd"

        def log_message(self, *a):  # 不记录请求内容
            pass

        def reply(self, code, obj):
            b = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

        def handle_any(self, method):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 2_000_000:
                return self.reply(413, {"error": "TOO_LARGE"})
            body = self.rfile.read(n) if n else b""
            if not runner.verify(method, self.path, self.headers, body):
                return self.reply(401, {"error": "UNAUTHORIZED"})
            u = urlsplit(self.path); parts = [p for p in u.path.split("/") if p]
            if method == "POST" and parts == ["v1", "jobs"]:
                try:
                    spec = json.loads(body or b"{}")
                except ValueError:
                    return self.reply(400, {"error": "BAD_JSON"})
                return self.reply(*runner.submit(spec))
            if method == "POST" and parts == ["v1", "acceptance"]:
                try:
                    spec = json.loads(body or b"{}")
                except ValueError:
                    return self.reply(400, {"error": "BAD_JSON"})
                return self.reply(*runner.submit(spec, "acceptance"))
            if method == "POST" and parts == ["v1", "review"]:
                try:
                    spec = json.loads(body or b"{}")
                except ValueError:
                    return self.reply(400, {"error": "BAD_JSON"})
                return self.reply(*runner.submit(spec, "review"))
            if len(parts) >= 3 and parts[:2] == ["v1", "jobs"]:
                job = runner.jobs.get(parts[2])
                if not job:
                    return self.reply(404, {"error": "NOT_FOUND"})
                if method == "GET" and len(parts) == 3:
                    return self.reply(200, job.view())
                if method == "GET" and parts[3:] == ["logs"]:
                    frm = int((parse_qs(u.query).get("from") or ["0"])[0])
                    with job.lock:
                        ev = job.logs[frm: frm + 500]
                    return self.reply(200, {"events": ev, "next": frm + len(ev)})
                if method == "POST" and parts[3:] == ["cancel"]:
                    runner.cancel(job, "cancelled"); return self.reply(200, {"ok": True})
            return self.reply(404, {"error": "NOT_FOUND"})

        def do_GET(self):
            self.handle_any("GET")

        def do_POST(self):
            self.handle_any("POST")
    return H


def main():
    r = Runner(CFG)
    srv = ThreadingHTTPServer((CFG["bind"], CFG["port"]), make_handler(r))
    print(f"x7x24-runnerd listening on {CFG['bind']}:{CFG['port']}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
