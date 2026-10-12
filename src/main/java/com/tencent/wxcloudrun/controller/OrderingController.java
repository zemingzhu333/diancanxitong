package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.service.AdminAccessGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import com.tencent.wxcloudrun.service.WechatSubscribeMessageService;

@RestController
@RequestMapping("/api")
public class OrderingController {
  private static final String[] ORDER_STATUSES = {"待接单", "制作中", "已完成", "已取消"};

  @Value("${app.customer-app-id}")
  private String customerAppId;

  private final JdbcTemplate jdbc;
  private final AdminAccessGuard adminAccess;
  private final WechatSubscribeMessageService subscribeMessages;

  public OrderingController(@Autowired JdbcTemplate jdbc, @Autowired AdminAccessGuard adminAccess,
                            @Autowired WechatSubscribeMessageService subscribeMessages) {
    this.jdbc = jdbc;
    this.adminAccess = adminAccess;
    this.subscribeMessages = subscribeMessages;
  }

  @GetMapping("/menu")
  public ApiResponse menu() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("categories", jdbc.queryForList(
        "SELECT category FROM dishes WHERE on_sale = 1 GROUP BY category ORDER BY MIN(sort_order), category", String.class));
    data.put("dishes", dishes(true));
    data.put("isOpen", storeIsOpen());
    return ApiResponse.ok(data);
  }

  @GetMapping("/orders/mine")
  public ApiResponse myOrders(HttpServletRequest request) {
    String openid = requireCustomer(request);
    if (openid == null) return ApiResponse.error("请从顾客端小程序访问");
    List<Map<String, Object>> orders = jdbc.query(
        "SELECT id, status, note, amount_cents, created_at FROM orders WHERE customer_openid = ? ORDER BY created_at DESC LIMIT 100",
        (rs, rowNum) -> orderRow(rs), openid);
    attachItems(orders);
    return ApiResponse.ok(orders);
  }

  @PostMapping("/orders")
  @Transactional
  public ApiResponse createOrder(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    String openid = requireCustomer(request);
    if (openid == null) return ApiResponse.error("请从顾客端小程序访问");
    if (!storeIsOpen()) return ApiResponse.error("门店已暂停接单");
    Object rawItems = body.get("items");
    if (!(rawItems instanceof List) || ((List<?>) rawItems).isEmpty()) return ApiResponse.error("购物车是空的");

    Map<String, Integer> quantities = new LinkedHashMap<>();
    for (Object raw : (List<?>) rawItems) {
      if (!(raw instanceof Map)) return ApiResponse.error("订单菜品格式无效");
      Map<?, ?> item = (Map<?, ?>) raw;
      String dishId = stringValue(item.get("dishId"));
      int quantity = integerValue(item.get("quantity"), 0);
      if (dishId.isEmpty() || quantity < 1 || quantity > 99) return ApiResponse.error("菜品数量无效");
      int combined = quantities.containsKey(dishId) ? quantities.get(dishId) + quantity : quantity;
      if (combined > 99) return ApiResponse.error("单个菜品最多点 99 份");
      quantities.put(dishId, combined);
    }
    if (quantities.size() > 50) return ApiResponse.error("每笔订单最多选择 50 款菜品");

    String orderId = "GT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    long amount = 0;
    List<Map<String, Object>> snapshots = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
      List<Map<String, Object>> matches = jdbc.query(
          "SELECT id, category, name, description, price_cents, emoji, cover FROM dishes WHERE id = ? AND on_sale = 1",
          (rs, rowNum) -> dishRow(rs), entry.getKey());
      if (matches.isEmpty()) return ApiResponse.error("有菜品已下架，请刷新菜单后重试");
      Map<String, Object> dish = matches.get(0);
      int quantity = entry.getValue();
      int price = (Integer) dish.get("price");
      amount += (long) price * quantity;
      if (amount > Integer.MAX_VALUE) return ApiResponse.error("订单金额超出限制");
      dish.put("quantity", quantity);
      snapshots.add(dish);
    }
    if (amount <= 0) return ApiResponse.error("订单金额无效");

    String note = stringValue(body.get("note"));
    if (note.length() > 500) return ApiResponse.error("备注不能超过 500 个字符");
    jdbc.update("INSERT INTO orders (id, customer_openid, status, note, amount_cents) VALUES (?, ?, '待接单', ?, ?)",
        orderId, openid, note, (int) amount);
    for (Map<String, Object> item : snapshots) {
      jdbc.update("INSERT INTO order_items (order_id, dish_id, dish_name, category, unit_price_cents, quantity, emoji, cover) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          orderId, item.get("id"), item.get("name"), item.get("category"), item.get("price"), item.get("quantity"), item.get("emoji"), item.get("cover"));
    }

    final List<Map<String, Object>> notificationItems = new ArrayList<>(snapshots);
    final int notificationAmount = (int) amount;
    final LocalDateTime notificationTime = LocalDateTime.now();
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        subscribeMessages.sendNewOrder(orderId, notificationItems, notificationAmount, notificationTime);
      }
    });

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", orderId);
    result.put("status", "待接单");
    result.put("note", note);
    result.put("items", snapshots);
    result.put("amount", (int) amount);
    result.put("createdAt", LocalDateTime.now().toString());
    return ApiResponse.ok(result);
  }

  @GetMapping("/admin/whoami")
  public ApiResponse whoAmI(HttpServletRequest request) {
    if (!adminAccess.isAdminApp(request)) return ApiResponse.error("请从小猪厨房管理端访问");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("openid", adminAccess.openid(request));
    data.put("authorized", adminAccess.isAuthorized(request));
    data.put("adminConfigured", adminAccess.isConfigured());
    return ApiResponse.ok(data);
  }

  @GetMapping("/admin/dishes")
  public ApiResponse adminDishes(HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    return ApiResponse.ok(dishes(false));
  }

  @PostMapping("/admin/dishes")
  public ApiResponse createDish(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    String id = "dish-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String name = stringValue(body.get("name")).trim();
    String category = stringValue(body.get("category")).trim();
    int price = integerValue(body.get("price"), 0);
    if (name.isEmpty() || category.isEmpty() || price <= 0 || name.length() > 100 || category.length() > 64
        || stringValue(body.get("description")).length() > 255) return ApiResponse.error("菜名、分类、描述或价格不符合要求");
    jdbc.update("INSERT INTO dishes (id, category, name, description, price_cents, emoji, cover, on_sale) VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
        id, category, name, stringValue(body.get("description")), price,
        defaultValue(body.get("emoji"), "🍲"), nullableString(body.get("cover")));
    return ApiResponse.ok(id);
  }

  @PutMapping("/admin/dishes/{id}")
  public ApiResponse updateDish(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    String name = stringValue(body.get("name")).trim();
    String category = stringValue(body.get("category")).trim();
    int price = integerValue(body.get("price"), 0);
    if (name.isEmpty() || category.isEmpty() || price <= 0 || name.length() > 100 || category.length() > 64
        || stringValue(body.get("description")).length() > 255) return ApiResponse.error("菜名、分类、描述或价格不符合要求");
    int changed = jdbc.update("UPDATE dishes SET category = ?, name = ?, description = ?, price_cents = ?, emoji = ?, cover = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        category, name, stringValue(body.get("description")), price,
        defaultValue(body.get("emoji"), "🍲"), nullableString(body.get("cover")), id);
    return changed == 0 ? ApiResponse.error("菜品不存在") : ApiResponse.ok(id);
  }

  @PatchMapping("/admin/dishes/{id}/sale")
  public ApiResponse setDishSale(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    boolean onSale = Boolean.TRUE.equals(body.get("onSale"));
    int changed = jdbc.update("UPDATE dishes SET on_sale = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", onSale, id);
    return changed == 0 ? ApiResponse.error("菜品不存在") : ApiResponse.ok();
  }

  @GetMapping("/admin/orders")
  public ApiResponse adminOrders(HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    List<Map<String, Object>> orders = jdbc.query(
        "SELECT id, status, note, amount_cents, created_at FROM orders ORDER BY created_at DESC LIMIT 100",
        (rs, rowNum) -> orderRow(rs));
    attachItems(orders);
    return ApiResponse.ok(orders);
  }

  @PatchMapping("/admin/orders/{id}")
  public ApiResponse setOrderStatus(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    String status = stringValue(body.get("status"));
    if (!Arrays.asList(ORDER_STATUSES).contains(status)) return ApiResponse.error("订单状态无效");
    List<String> currentStatuses = jdbc.queryForList("SELECT status FROM orders WHERE id = ?", String.class, id);
    if (currentStatuses.isEmpty()) return ApiResponse.error("订单不存在");
    String current = currentStatuses.get(0);
    boolean allowed = ("待接单".equals(current) && ("制作中".equals(status) || "已取消".equals(status)))
        || ("制作中".equals(current) && ("已完成".equals(status) || "已取消".equals(status)));
    if (!allowed) return ApiResponse.error("当前订单状态不能执行此操作");
    jdbc.update("UPDATE orders SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
    return ApiResponse.ok();
  }

  @GetMapping("/admin/settings")
  public ApiResponse adminSettings(HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("isOpen", storeIsOpen());
    return ApiResponse.ok(data);
  }

  @PutMapping("/admin/settings/open")
  public ApiResponse setStoreOpen(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    if (!requireAdmin(request)) return ApiResponse.error("管理端未授权，请联系管理员配置账号");
    boolean isOpen = Boolean.TRUE.equals(body.get("isOpen"));
    jdbc.update("INSERT INTO store_settings (setting_key, setting_value) VALUES ('is_open', ?) ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)",
        isOpen ? "true" : "false");
    return ApiResponse.ok(isOpen);
  }

  private String requireCustomer(HttpServletRequest request) {
    // In a resource-reuse call, CloudBase puts the consumer mini-program identity
    // in X-WX-FROM-*; ordinary X-WX-* headers may identify the resource owner.
    String appid = firstNonBlank(request.getHeader("X-WX-FROM-APPID"), request.getHeader("X-WX-APPID"));
    String openid = firstNonBlank(request.getHeader("X-WX-FROM-OPENID"), request.getHeader("X-WX-OPENID"));
    return customerAppId.equals(appid) && openid != null && !openid.trim().isEmpty() ? openid.trim() : null;
  }

  private String firstNonBlank(String first, String fallback) {
    if (first != null && !first.trim().isEmpty()) return first.trim();
    return fallback == null ? null : fallback.trim();
  }

  private boolean requireAdmin(HttpServletRequest request) {
    return adminAccess.isConfigured() && adminAccess.isAuthorized(request);
  }

  private List<Map<String, Object>> dishes(boolean onlyOnSale) {
    String sql = "SELECT id, category, name, description, price_cents, emoji, cover, on_sale FROM dishes";
    if (onlyOnSale) sql += " WHERE on_sale = 1";
    sql += " ORDER BY sort_order, created_at DESC";
    return jdbc.query(sql, (rs, rowNum) -> dishRow(rs));
  }

  private Map<String, Object> dishRow(ResultSet rs) throws SQLException {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", rs.getString("id"));
    item.put("category", rs.getString("category"));
    item.put("name", rs.getString("name"));
    item.put("description", rs.getString("description"));
    item.put("price", rs.getInt("price_cents"));
    item.put("emoji", rs.getString("emoji"));
    item.put("cover", rs.getString("cover"));
    item.put("onSale", rs.getBoolean("on_sale"));
    item.put("soldOut", false);
    item.put("tone", "tomato");
    return item;
  }

  private Map<String, Object> orderRow(ResultSet rs) throws SQLException {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", rs.getString("id"));
    order.put("status", rs.getString("status"));
    order.put("note", rs.getString("note"));
    order.put("amount", rs.getInt("amount_cents"));
    Timestamp createdAt = rs.getTimestamp("created_at");
    order.put("createdAt", createdAt == null ? "" : createdAt.toLocalDateTime().toString().replace('T', ' '));
    order.put("items", new ArrayList<Map<String, Object>>());
    return order;
  }

  @SuppressWarnings("unchecked")
  private void attachItems(List<Map<String, Object>> orders) {
    for (Map<String, Object> order : orders) {
      List<Map<String, Object>> items = jdbc.query(
          "SELECT dish_id, dish_name, category, unit_price_cents, quantity, emoji, cover FROM order_items WHERE order_id = ? ORDER BY id",
          (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("dish_id"));
            item.put("name", rs.getString("dish_name"));
            item.put("category", rs.getString("category"));
            item.put("price", rs.getInt("unit_price_cents"));
            item.put("quantity", rs.getInt("quantity"));
            item.put("emoji", rs.getString("emoji"));
            item.put("cover", rs.getString("cover"));
            return item;
          }, order.get("id"));
      order.put("items", items);
    }
  }

  private boolean storeIsOpen() {
    try {
      return "true".equalsIgnoreCase(jdbc.queryForObject(
          "SELECT setting_value FROM store_settings WHERE setting_key = 'is_open'", String.class));
    } catch (EmptyResultDataAccessException exception) {
      return true;
    }
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String nullableString(Object value) {
    String result = stringValue(value).trim();
    return result.isEmpty() ? null : result;
  }

  private String defaultValue(Object value, String fallback) {
    String result = stringValue(value).trim();
    return result.isEmpty() ? fallback : result;
  }

  private int integerValue(Object value, int fallback) {
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return Integer.parseInt(stringValue(value));
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }
}
