package com.x7x24.web;

import java.util.Map;

/** 给用户看的业务错误：code 供前端识别，message 为中文、可行动的说明，params 为补充数据（放在「详情」里） */
public class UserError extends IllegalArgumentException {
  public final String code; public final Map<String, Object> params;
  public UserError(String code, String message) { this(code, message, Map.of()); }
  public UserError(String code, String message, Map<String, Object> params) { super(message); this.code = code; this.params = params; }
}
