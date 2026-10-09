package com.tencent.wxcloudrun.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class WechatSubscribeMessageService {
  private static final Logger logger = LoggerFactory.getLogger(WechatSubscribeMessageService.class);
  private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  @Value("${app.admin-app-id}") private String adminAppId;
  @Value("${app.admin-app-secret:}") private String adminAppSecret;
  @Value("${app.admin-openids:}") private String adminOpenids;
  @Value("${app.subscription.template-id:}") private String templateId;

  private final RestTemplate http = new RestTemplate();
  private final ObjectMapper mapper = new ObjectMapper();
  private String cachedAccessToken;
  private long tokenExpiresAt;

  public void sendNewOrder(String orderId, List<Map<String, Object>> items, int amountCents, LocalDateTime createdAt) {
    List<String> recipients = Arrays.stream(adminOpenids.split(","))
        .map(String::trim).filter(value -> !value.isEmpty()).distinct().collect(java.util.stream.Collectors.toList());
    if (recipients.isEmpty() || isBlank(adminAppSecret) || isBlank(templateId)) {
      logger.warn("New-order subscription message skipped because server configuration is incomplete");
      return;
    }
    try {
      String token = accessToken();
      for (String recipient : recipients) {
        try {
          sendOne(token, recipient, orderId, items, amountCents, createdAt);
        } catch (Exception exception) {
          logger.warn("New-order subscription message failed for one admin ({})", exception.getClass().getSimpleName());
        }
      }
    } catch (Exception exception) {
      logger.warn("New-order subscription message could not be sent ({})", exception.getClass().getSimpleName());
    }
  }

  private synchronized String accessToken() throws Exception {
    long now = System.currentTimeMillis();
    if (!isBlank(cachedAccessToken) && now < tokenExpiresAt) return cachedAccessToken;
    String url = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/cgi-bin/token")
        .queryParam("grant_type", "client_credential")
        .queryParam("appid", adminAppId)
        .queryParam("secret", adminAppSecret)
        .build().encode().toUriString();
    JsonNode response = mapper.readTree(http.getForObject(url, String.class));
    if (response.hasNonNull("errcode") && response.path("errcode").asInt() != 0) {
      throw new IllegalStateException("WeChat token API rejected the request");
    }
    String token = response.path("access_token").asText("");
    if (token.isEmpty()) throw new IllegalStateException("WeChat token API returned no token");
    int expiresIn = response.path("expires_in").asInt(7200);
    cachedAccessToken = token;
    tokenExpiresAt = now + Math.max(60, expiresIn - 300) * 1000L;
    return cachedAccessToken;
  }

  private void sendOne(String token, String recipient, String orderId, List<Map<String, Object>> items,
                       int amountCents, LocalDateTime createdAt) throws Exception {
    String url = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/cgi-bin/message/subscribe/send")
        .queryParam("access_token", token).build().encode().toUriString();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("touser", recipient);
    payload.put("template_id", templateId);
    payload.put("page", "pages/orders/orders");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("thing2", value(orderSummary(items)));
    data.put("time4", value((createdAt == null ? LocalDateTime.now() : createdAt).format(MESSAGE_TIME)));
    data.put("amount10", value(String.format(Locale.ROOT, "¥%.2f", amountCents / 100.0)));
    data.put("thing18", value("堂食顾客"));
    payload.put("data", data);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String raw = http.postForObject(url, new HttpEntity<>(payload, headers), String.class);
    JsonNode response = mapper.readTree(raw == null ? "{}" : raw);
    int errorCode = response.path("errcode").asInt(-1);
    if (errorCode != 0) logger.warn("New-order subscription message rejected for one admin (errcode={})", errorCode);
  }

  private String orderSummary(List<Map<String, Object>> items) {
    StringBuilder summary = new StringBuilder();
    for (Map<String, Object> item : items) {
      String part = String.valueOf(item.get("name")) + "×" + item.get("quantity");
      String candidate = summary.length() == 0 ? part : summary + "、" + part;
      if (candidate.length() > 20) break;
      summary.setLength(0);
      summary.append(candidate);
    }
    if (summary.length() == 0) summary.append("新订单");
    return summary.toString();
  }

  private Map<String, String> value(String value) {
    Map<String, String> field = new LinkedHashMap<>();
    field.put("value", value);
    return field;
  }

  private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
