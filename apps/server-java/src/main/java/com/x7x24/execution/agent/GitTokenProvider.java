package com.x7x24.execution.agent;

/**
 * 为单个 job 提供 clone 用的 GitHub 令牌。runner 只用它在宿主侧 clone，不进容器、不落库。
 * 当前实现取项目已连接的凭据；Phase 3 改为 GitHub App 安装令牌（单仓库、contents:read、1 小时过期）。
 */
@FunctionalInterface
public interface GitTokenProvider { String tokenFor(String repo); }
