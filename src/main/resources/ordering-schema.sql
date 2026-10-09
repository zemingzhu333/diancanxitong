CREATE TABLE IF NOT EXISTS `dishes` (
  `id` varchar(64) NOT NULL,
  `category` varchar(64) NOT NULL,
  `name` varchar(100) NOT NULL,
  `description` varchar(255) NOT NULL DEFAULT '',
  `price_cents` int unsigned NOT NULL,
  `emoji` varchar(16) NOT NULL DEFAULT '🍲',
  `cover` varchar(512) DEFAULT NULL,
  `on_sale` tinyint(1) NOT NULL DEFAULT 1,
  `sort_order` int NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dishes_category_sale` (`category`, `on_sale`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `orders` (
  `id` varchar(40) NOT NULL,
  `customer_openid` varchar(128) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT '待接单',
  `note` varchar(500) NOT NULL DEFAULT '',
  `amount_cents` int unsigned NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_orders_customer_created` (`customer_openid`, `created_at`),
  KEY `idx_orders_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `order_items` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `order_id` varchar(40) NOT NULL,
  `dish_id` varchar(64) NOT NULL,
  `dish_name` varchar(100) NOT NULL,
  `category` varchar(64) NOT NULL,
  `unit_price_cents` int unsigned NOT NULL,
  `quantity` int unsigned NOT NULL,
  `emoji` varchar(16) NOT NULL DEFAULT '🍲',
  `cover` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_order_items_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `store_settings` (
  `setting_key` varchar(64) NOT NULL,
  `setting_value` varchar(255) NOT NULL,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `store_settings` (`setting_key`, `setting_value`) VALUES ('is_open', 'true');
INSERT IGNORE INTO `dishes` (`id`, `category`, `name`, `description`, `price_cents`, `emoji`) VALUES
  ('d1', '招牌锅物', '番茄肥牛锅', '酸甜番茄汤底 · 鲜切肥牛', 3800, '🍅'),
  ('d2', '招牌锅物', '菌菇鸡汤锅', '每日慢熬鸡汤 · 山野菌菇', 4200, '🍲'),
  ('d3', '招牌锅物', '麻辣牛油锅', '醇厚牛油 · 香而不燥', 4600, '🌶️'),
  ('d4', '涮煮好物', '鲜切雪花肥牛', '一份约 180g', 3200, '🥩'),
  ('d5', '涮煮好物', '手打虾滑', '鲜虾手打 · Q弹爽口', 2600, '🍤'),
  ('d6', '主食小吃', '手工红糖糍粑', '现炸外酥里糯', 1800, '🍡');
