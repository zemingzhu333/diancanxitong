CREATE TABLE IF NOT EXISTS `dish_categories` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dish_categories_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `dish_categories` (`name`)
SELECT DISTINCT TRIM(`category`) FROM `dishes` WHERE TRIM(`category`) <> '' ORDER BY TRIM(`category`);
