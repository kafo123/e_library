-- =====================================================================
-- 种子数据(幂等:INSERT IGNORE,重复启动不会重复插入)
-- =====================================================================

INSERT IGNORE INTO `users` (`id`, `name`, `email`, `created_at`) VALUES
  (1, '张三', 'zhangsan@example.com', NOW()),
  (2, '李四', 'lisi@example.com', NOW()),
  (3, '王五', 'wangwu@example.com', NOW());

INSERT IGNORE INTO `book`
  (`id`, `title`, `author`, `isbn`, `type`, `category`, `description`, `total_copies`, `available_copies`, `created_at`, `updated_at`)
VALUES
  (1, 'Effective Java', 'Joshua Bloch', '9780134685991', 'EBOOK', '编程', 'Java 编程经典著作,第 3 版。', 3, 3, NOW(), NOW()),
  (2, '深入理解 Java 虚拟机', '周志明', '9787111641247', 'EBOOK', '编程', 'JVM 原理、内存模型与调优实战。', 2, 2, NOW(), NOW()),
  (3, '代码整洁之道', 'Robert C. Martin', '9787115216878', 'EBOOK', '编程', 'Clean Code 中文版,代码质量与可维护性指南。', 2, 2, NOW(), NOW()),
  (4, '三体', '刘慈欣', '9787536692930', 'EBOOK', '科幻', '地球往事三部曲之一,雨果奖获奖作品。', 5, 5, NOW(), NOW()),
  (5, 'Nature', 'Springer Nature', NULL, 'JOURNAL', '科学', '顶级综合科学期刊。', 10, 10, NOW(), NOW()),
  (6, '科学美国人', 'Scientific American', NULL, 'JOURNAL', '科学', '经典科普期刊。', 8, 8, NOW(), NOW()),
  (7, 'IEEE Software', 'IEEE', NULL, 'JOURNAL', '工程', '软件工程领域期刊。', 6, 6, NOW(), NOW()),
  (8, 'AI 时代的热门新书', '某作者', NULL, 'EBOOK', '科技', '仅 1 副本的热门书,供并发场景验证。', 1, 1, NOW(), NOW());
