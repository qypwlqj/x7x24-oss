package com.x7x24.unit;

import com.x7x24.chat.ChatActions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatActionsParseTest {
  @Test void parsesExplicitCommands() {
    var a = ChatActions.parse("新增产品线「教育」"); assertEquals(ChatActions.CREATE_LINE, a.kind()); assertEquals("教育", a.args().path("name").asText());
    var b = ChatActions.parse("新建项目 官网 到产品线 教育 仓库 acme/site"); assertEquals(ChatActions.CREATE_PROJECT, b.kind());
    assertEquals("官网", b.args().path("name").asText()); assertEquals("教育", b.args().path("productLineName").asText()); assertEquals("acme/site", b.args().path("repo").asText());
    var c = ChatActions.parse("邀请 @octo-cat 为只读"); assertEquals(ChatActions.INVITE, c.kind()); assertEquals("octo-cat", c.args().path("login").asText()); assertEquals("只读", c.args().path("role").asText());
  }
  @Test void ordinaryRequirementsGoToModel() {
    assertNull(ChatActions.parse("成绩页想加个导出"));
    assertNull(ChatActions.parse("订单列表接口加个分页，产品线那边也要通知"));
    assertNull(ChatActions.parse("邀请"));
  }

  @org.junit.jupiter.api.Test void bindRepoRule() {
    var a = com.x7x24.chat.ChatActions.parse("给官网绑定仓库 acme/site");
    org.junit.jupiter.api.Assertions.assertEquals("bind_repo", a.kind()); org.junit.jupiter.api.Assertions.assertEquals("官网", a.args().path("projectName").asText()); org.junit.jupiter.api.Assertions.assertEquals("acme/site", a.args().path("repo").asText());
    var b = com.x7x24.chat.ChatActions.parse("绑定仓库 site");
    org.junit.jupiter.api.Assertions.assertEquals("bind_repo", b.kind()); org.junit.jupiter.api.Assertions.assertFalse(b.args().has("projectName"));
    org.junit.jupiter.api.Assertions.assertNull(com.x7x24.chat.ChatActions.parse("我想把仓库的 README 改一下"));
  }
}
