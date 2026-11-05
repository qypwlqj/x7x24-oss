package com.x7x24.notification;

import com.x7x24.adapters.Redactor;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.net.URI;

/** 平台统一发信；用户只提供收件地址。构造时不连接 SMTP。 */
@Component
public class NotificationMail {
  private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
  private final String from, appUrl;
  public NotificationMail(Environment env) {
    String base = "x7x24.notifications.";
    sender.setHost(env.getProperty(base + "smtp.host", ""));
    sender.setPort(env.getProperty(base + "smtp.port", Integer.class, 587));
    sender.setUsername(env.getProperty(base + "smtp.username", ""));
    String password = env.getProperty(base + "smtp.password", "");
    sender.setPassword(password); Redactor.register(password);
    sender.setDefaultEncoding("UTF-8");
    from = env.getProperty(base + "from", "").trim();
    appUrl = env.getProperty(base + "app-url", "").trim().replaceAll("/+$", "");
    var p = sender.getJavaMailProperties();
    boolean ssl = env.getProperty(base + "smtp.ssl", Boolean.class, false);
    boolean tls = env.getProperty(base + "smtp.starttls", Boolean.class, true) && !ssl;
    p.setProperty("mail.smtp.auth", String.valueOf(!sender.getUsername().isBlank()));
    p.setProperty("mail.smtp.ssl.enable", String.valueOf(ssl));
    p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    p.setProperty("mail.smtp.starttls.enable", String.valueOf(tls));
    p.setProperty("mail.smtp.starttls.required", String.valueOf(tls));
    p.setProperty("mail.smtp.connectiontimeout", "5000");
    p.setProperty("mail.smtp.timeout", "5000");
    p.setProperty("mail.smtp.writetimeout", "5000");
  }
  public boolean ready() { return sender.getHost() != null && !sender.getHost().isBlank() && !from.isBlank() && !from.contains("\n") && !from.contains("\r") && validUrl(appUrl); }
  private static boolean validUrl(String value) {
    try { var u = URI.create(value); return ("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null && u.getRawFragment() == null && u.getRawQuery() == null && u.getUserInfo() == null; }
    catch (IllegalArgumentException e) { return false; }
  }
  public String taskUrl(String taskId) { return validUrl(appUrl) ? appUrl + "/t/" + java.net.URLEncoder.encode(taskId, java.nio.charset.StandardCharsets.UTF_8) : ""; }
  public void send(String to, String subject, String body) {
    if (!ready()) throw new IllegalStateException("系统尚未配置发信服务");
    var m = new SimpleMailMessage(); m.setFrom(from); m.setTo(to);
    m.setSubject(subject.replaceAll("[\\r\\n]", " ")); m.setText(body);
    sender.send(m);
  }
}
