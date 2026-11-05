package com.x7x24.gov;

import com.x7x24.adapters.Redactor;
import com.x7x24.engine.ProjectCfg;

import java.util.*;
import java.util.regex.Pattern;

/**
 * agent 产出文件的路径白名单 / 禁区 / 内容检查。不信任任何 agent（含 LLM）给出的路径与内容：
 * 只允许项目配置的目录与根目录 README.md / CHANGELOG.md 下的文本源码；
 * 禁止 .github/、CI 入口 scripts/、CODEOWNERS、deploy/、密钥文件、依赖清单与锁文件等；内容里不得含已知密钥或环境变量外泄代码。
 */
public final class PathPolicy {
  private PathPolicy() {}
  private static final Set<String> ALLOW_ROOT = Set.of("README.md", "CHANGELOG.md");
  private static final List<String> DENY_SEGMENT = List.of(".git", ".github", "node_modules", ".ssh", ".aws", "deploy", "acceptance", "scripts");
  private static final Pattern DENY_NAME = Pattern.compile("(?i)^(\\.env.*|.*\\.(pem|key|p12|pfx|crt|keystore)|id_(rsa|ed25519|ecdsa).*|codeowners|package(-lock)?\\.json|yarn\\.lock|pnpm-lock\\.yaml|dockerfile|.*secret.*|.*credential.*|\\.gitleaks.*|.*semgrep.*|.*branch-protection.*)$");

  /** 返回 null 表示路径可接受，否则返回拒绝原因。 */
  public static String checkPath(String path) {
    return checkPath(new ProjectCfg(), path);
  }
  public static String checkPath(ProjectCfg project, String path) {
    if (path == null || path.isBlank()) return "空路径";
    if (path.length() > 200 || path.indexOf('\0') >= 0 || path.contains("\\") || path.startsWith("/") || path.startsWith("~") || path.contains("..") || path.contains("//")) return "非法路径";
    String[] seg = path.split("/");
    for (String s : seg) { if (s.isEmpty() || s.equals(".")) return "非法路径"; if (DENY_SEGMENT.contains(s.toLowerCase())) return "禁区目录 " + s; if (DENY_NAME.matcher(s).matches()) return "禁区文件 " + s; }
    boolean ok = ALLOW_ROOT.contains(path) || project.allowedPaths.stream().anyMatch(path::startsWith);
    if (!ok) return "不在项目路径白名单";
    String name = seg[seg.length - 1]; int dot = name.lastIndexOf('.');
    if (dot < 0 || !project.allowedExtensions.contains(name.substring(dot + 1).toLowerCase())) return "不允许的文件类型";
    if ("forbidden".equals(project.riskOf(path))) return "项目禁止修改的路径";
    return null;
  }

  /** 内容检查：已知密钥、疑似凭据、环境变量读取 / 外联执行（外泄手法）。 */
  public static String checkContent(String path, String content) {
    return checkContent(new ProjectCfg(),path,content);
  }
  public static String checkContent(ProjectCfg project,String path,String content) {
    if (content == null) return null;
    if (Redactor.containsSecret(content)) return "内容含疑似凭据 / 已知密钥";
    if (!path.endsWith(".md") && !path.endsWith(".txt")) {
      var finding=CodeContentPolicy.check(project,path,content);
      if(finding!=null)return finding.reason()+"（"+finding.code()+"，行 "+(1+content.substring(0,finding.offset()).chars().filter(c->c=='\n').count())+"）";
    }
    if (content.length() > 100_000) return "文件过大";
    return null;
  }

  public record Violation(String path, String reason,String code,String type,boolean repairable) {
    public Violation(String path,String reason){this(path,reason,"PATH_POLICY","path",true);}
  }
  public static List<Violation> check(Collection<String> paths, Map<String, String> contents) {
    return check(new ProjectCfg(), paths, contents);
  }
  public static List<Violation> check(ProjectCfg project, Collection<String> paths, Map<String, String> contents) {
    List<Violation> out = new ArrayList<>();
    for (String p : paths) {
      String r=checkPath(project,p);
      if(r!=null){out.add(new Violation(p,r));continue;}
      r=checkContent(project,p,contents.get(p));
      if(r!=null)out.add(new Violation(p,r,"CONTENT_POLICY","content",true));
    }
    return out;
  }
}
