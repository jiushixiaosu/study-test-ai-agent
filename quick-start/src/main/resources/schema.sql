-- 会话记忆表（study-ai 库）。IF NOT EXISTS 保证重复启动安全。
-- 兼容性说明：
-- 1) metadata 用 LONGTEXT 存 JSON 文本，而非 JSON 类型——JSON 类型需 MySQL 5.7+，
--    旧版 MySQL/MariaDB 不支持，会导致建表失败（启动报 execute SQL script statement #1）。
--    代码侧 metadata 本就是 JSON 字符串，LONGTEXT 完全够用。
-- 2) created_at 用 DATETIME 而非 DATETIME(3)——兼容 MySQL 5.6.4 以下版本。
CREATE TABLE IF NOT EXISTS chat_memory (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  title           VARCHAR(255) NULL,       -- 会话标题：首次对话的提问，用于列表展示
  msg_order       INT          NOT NULL,   -- 会话内序号，保证读回顺序（而非依赖 created_at）
  msg_type        VARCHAR(20)  NOT NULL,   -- USER / ASSISTANT / SYSTEM / TOOL
  content         LONGTEXT     NOT NULL,   -- 消息文本
  metadata        LONGTEXT     NULL,       -- 元数据（工具调用等），JSON 字符串
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_conv_order (conversation_id, msg_order),
  KEY idx_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
