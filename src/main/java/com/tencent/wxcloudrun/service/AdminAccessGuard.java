package com.tencent.wxcloudrun.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

@Component
public class AdminAccessGuard {
  @Value("${app.admin-app-id}")
  private String adminAppId;

  public String openid(HttpServletRequest request) {
    return firstNonBlank(request.getHeader("X-WX-FROM-OPENID"), request.getHeader("X-WX-OPENID"));
  }

  public boolean isAdminApp(HttpServletRequest request) {
    return adminAppId.equals(firstNonBlank(request.getHeader("X-WX-FROM-APPID"), request.getHeader("X-WX-APPID")));
  }

  public boolean isAuthorized(HttpServletRequest request) {
    String openid = openid(request);
    return openid != null && !openid.trim().isEmpty() && isAdminApp(request);
  }

  private String firstNonBlank(String first, String fallback) {
    if (first != null && !first.trim().isEmpty()) return first.trim();
    return fallback == null ? null : fallback.trim();
  }
}
