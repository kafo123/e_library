-- =====================================================================
-- E-Library 数据库结构(MySQL 8)
-- 说明:
--  1. 表名 users(而非 user):user 是 PostgreSQL 等数据库保留字,避免未来跨库迁移问题
--  2. 时间列不依赖 DEFAULT/ON UPDATE,统一由 SQL 显式赋值,行为显式可控
--  3. 唯一索引允许 active_flag 为多个 NULL,利用 MySQL "多 NULL 不冲突" 技巧
--     实现:同一 (user, book) 仅一条活跃借阅,历史归还记录不受约束
-- =====================================================================

CREATE TABLE IF NOT EXISTS `users` (
  `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
  `name`       VARCHAR(64)  NOT NULL,
  `email`      VARCHAR(128) NULL,
  `created_at` DATETIME     NOT NULL
);

CREATE TABLE IF NOT EXISTS `book` (
  `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
  `title`            VARCHAR(255) NOT NULL,
  `author`           VARCHAR(128) NOT NULL,
  `isbn`             VARCHAR(32)  NULL,
  `type`             VARCHAR(16)  NOT NULL,   -- EBOOK / JOURNAL
  `category`         VARCHAR(64)  NULL,
  `description`      TEXT         NULL,
  `total_copies`     INT          NOT NULL,
  `available_copies` INT          NOT NULL,
  `created_at`       DATETIME     NOT NULL,
  `updated_at`       DATETIME     NOT NULL,
  CONSTRAINT `uk_book_isbn` UNIQUE (`isbn`),
  INDEX `idx_book_type_category` (`type`, `category`)
);

CREATE TABLE IF NOT EXISTS `borrow_record` (
  `id`                BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id`           BIGINT      NOT NULL,
  `book_id`           BIGINT      NOT NULL,
  `borrowed_at`       DATETIME    NOT NULL,
  `due_at`            DATETIME    NOT NULL,
  `returned_at`       DATETIME    NULL,
  `status`            VARCHAR(16) NOT NULL,   -- BORROWED / RETURNED
  `active_flag`       TINYINT     NULL,       -- 借阅中=1,已归还=NULL
  `created_at`        DATETIME    NOT NULL,
  `updated_at`        DATETIME    NOT NULL,
  CONSTRAINT `uk_user_book_active` UNIQUE (`user_id`, `book_id`, `active_flag`),
  INDEX `idx_record_user_status` (`user_id`, `status`)
);
