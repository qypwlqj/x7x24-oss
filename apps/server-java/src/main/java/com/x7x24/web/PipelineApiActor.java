package com.x7x24.web;

import jakarta.servlet.http.HttpServletRequest;

/** 当前请求的操作人（登录用户名；未开启登录时为 operator） */
public final class PipelineApiActor {
  private PipelineApiActor() {}
  public static String of(HttpServletRequest r) { return PipelineApi.actor(r); }
}
