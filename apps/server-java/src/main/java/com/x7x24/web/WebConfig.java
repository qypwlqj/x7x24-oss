package com.x7x24.web;

import com.x7x24.engine.X7Props;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.*;

/** CORS（开发时 vite 代理 / 独立前端）+ 由 Spring 直接托管前端静态资源（SPA 回退到 index.html）。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final X7Props props; private final com.x7x24.auth.AuthService auth;
  public WebConfig(X7Props props, com.x7x24.auth.AuthService auth) { this.props = props; this.auth = auth; }

  /** CORS 过滤器排在鉴权之前，401 也带 CORS 头。配置了 X7_ALLOWED_ORIGINS 时只放行这些源并允许携带凭据；否则默认拒绝所有跨域请求（同源与 vite 代理不受影响），并在启动日志告警。 */
  @org.springframework.context.annotation.Bean
  public org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.CorsFilter> corsFilter() {
    var c = new org.springframework.web.cors.CorsConfiguration();
    if (auth.allowedOrigins.isEmpty()) {
      org.slf4j.LoggerFactory.getLogger(WebConfig.class).warn("X7_ALLOWED_ORIGINS 未配置：已拒绝所有跨域请求。前端与后端不同源时，请把前端地址加入 X7_ALLOWED_ORIGINS");
      c.setAllowedOriginPatterns(java.util.List.of()); c.setAllowCredentials(false);
    }
    else { c.setAllowedOriginPatterns(auth.allowedOrigins); c.setAllowCredentials(true); }
    c.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")); c.addAllowedHeader("*"); c.setMaxAge(3600L);
    var src = new org.springframework.web.cors.UrlBasedCorsConfigurationSource(); src.registerCorsConfiguration("/api/**", c);
    var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new org.springframework.web.filter.CorsFilter(src));
    reg.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE); return reg;
  }

  @Override public void addViewControllers(ViewControllerRegistry r) { r.addViewController("/").setViewName("forward:/index.html"); }

  @Override public void addResourceHandlers(ResourceHandlerRegistry r) {
    Path dir = Paths.get(props.webDir).toAbsolutePath().normalize();
    if (!Files.isDirectory(dir)) return;
    r.addResourceHandler("/**").addResourceLocations(dir.toUri().toString()).resourceChain(true).addResolver(new PathResourceResolver() {
      @Override protected Resource getResource(String path, Resource location) throws IOException {
        if (path.startsWith("api/")) return null;
        Resource res = location.createRelative(path);
        return res.exists() && res.isReadable() ? res : new FileSystemResource(dir.resolve("index.html"));
      }
    });
  }
}
