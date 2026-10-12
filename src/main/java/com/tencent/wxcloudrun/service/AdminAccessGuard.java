package com.tencent.wxcloudrun.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AdminAccessGuard {
  @Value("${app.admin-app-id}")
  private String adminAppId;

  @Value("${app.admin-openids:}")
  private String adminOpenids;

  public String openid(HttpServletRequest request) {
    return firstNonBlank(request.getHeader("X-WX-FROM-OPENID"), request.getHeader("X-WX-OPENID"));
  }

  public boolean isAdminApp(HttpServletRequest request) {
    return adminAppId.equals(firstNonBlank(request.getHeader("X-WX-FROM-APPID"), request.getHeader("X-WX-APPID")));
  }

  public boolean isAuthorized(HttpServletRequest request) {
    String openid = openid(request);
    if (openid == null || openid.trim().isEmpty() || !isAdminApp(request)) {
      return false;
    }
    Set<String> allowed = Arrays.stream(adminOpenids.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));
    return allowed.contains(openid.trim());
  }

  public boolean isConfigured() {
    return adminOpenids != null && !adminOpenids.trim().isEmpty();
  }

  private String firstNonBlank(String first, String fallback) {
    if (first != null && !first.trim().isEmpty()) return first.trim();
    return fallback == null ? null : fallback.trim();
  }
}
