package com.x7x24.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 定价页与政策草案暂不公开：直接访问可以打开，但告诉搜索引擎不要收录（hash 路由的 #/pricing 由前端 meta 处理） */
@Component
public class NoindexFilter implements Filter {
  static boolean hidden(String p) { return p.equals("/pricing") || p.equals("/pricing/") || p.startsWith("/legal/"); }
  @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
    if (hidden(((HttpServletRequest) req).getRequestURI())) ((HttpServletResponse) res).setHeader("X-Robots-Tag", "noindex, nofollow");
    chain.doFilter(req, res);
  }
}
