# Spring AI 情感助手 Agent

基于 **Spring AI 1.1.7** 构建的 AI Agent 项目。以「情感咨询助手」为业务场景，完整实践了大模型对话、RAG 知识库、工具调用、显式决策循环等 Agent 核心能力。

> 从「工具增强型对话助手」演进到「具备自主决策循环的 Agent」的完整实现示例。

## 核心特性

| 能力 | 说明 |
|------|------|
| **多轮对话** | 情感助手「心屿」人设，系统提示词以代码常量管理 |
| **持久化记忆** | 自定义 `MysqlChatMemory`：全量追加写 + 窗口裁剪读 + 首问生成会话标题 |
| **RAG 知识库** | 阿里云百炼 Embedding + 向量检索；支持启动自动加载与运行时灌文档 |
| **工具调用** | 天气查询、周边 POI 搜索、图片搜索、图片内容识别 |
| **显式决策循环** | 自研 ReAct Loop，**决策步骤可见、步数可控、可防死循环** |
| **多模态** | 聊天记录截图识别与分析（智谱 GLM-4V-Flash） |
| **并发控制** | Redis 分布式锁保证同一会话串行，避免并发写记忆冲突 |
| **向量库持久化** | 继承 `SimpleVectorStore` 实现 JSON 快照，重启不丢、避免重复向量化 |
| **每日单词推送** | AI 结构化生成单词卡片 → Server酱推送微信 |
| **MCP Server** | 独立模块，将工具能力通过 MCP 协议暴露给外部客户端 |

## 技术栈

**后端**

- Java 17 / Spring Boot 3.5.7 / Spring AI 1.1.7
- DeepSeek-chat（主对话）、智谱 GLM-4V-Flash（视觉）、阿里云百炼 text-embedding-v3（向量化）
- MySQL（会话记忆）、Redis + Redisson（分布式锁）
- SimpleVectorStore + JSON 快照（向量存储）

**前端**

- Vue 3.5 + Vite 6
- markdown-it（消息渲染）+ highlight.js（代码高亮）
- axios

## 架构与数据流

```
                         Vue 3 前端
                             │
                    ┌────────┴────────┐
                    │  /api/chat      │  ChatClient 隐式工具循环
                    │  /api/agent/chat│  显式 ReAct 决策循环
                    └────────┬────────┘
                             │
                  Redis 分布式锁（同 chatId 串行）
                             │
                    ┌────────▼────────┐
                    │   ChatModel     │  DeepSeek-chat
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   会话记忆               RAG 检索              工具调用
  (MySQL)          (向量库 + Embedding)    (天气/POI/图片)
        │                    │                    │
   chat_memory          knowledge/*.md       第三方 API
                         + 运行时灌入
```

**显式决策循环（`/api/agent/chat`）的执行流程**

```
① 观察：组装 系统提示词 + RAG 参考资料 + 历史记忆 + 用户输入
② 决策：模型返回「调用工具」或「给出答案」
③ 行动：有工具调用 → 执行并记录步骤（工具名 + 参数）
④ 反馈：工具结果并入历史 → 回到 ①
（直到模型不再需要工具，或触达 maxSteps 上限）
```

## 快速开始

### 前置要求

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | |
| MySQL | 5.7+ / 8.0 | 会话记忆持久化 |
| Redis | 5+ | 分布式锁 |
| Node.js | 18+ | 前端构建 |

### 1. 克隆与配置

```bash
git clone <repo-url>
cd study-test-ai

# 复制敏感配置模板并填入自己的 Key
cp application-secret.properties.example \
   quick-start/src/main/resources/application-secret.properties
```

编辑 `application-secret.properties`，至少填写：

- `spring.ai.deepseek.api-key`（必填，主对话）
- `dashscope.api-key`（必填，RAG 向量化）
- `spring.datasource.password`（必填，MySQL 密码）

其余 Key 可选，不填则对应工具不可用。

### 2. 准备数据库

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS \`study-ai\` CHARACTER SET utf8mb4;"
```

> 表结构会在应用启动时由 `schema.sql` 自动创建，无需手动建表。

### 3. 启动后端

```bash
# 确保 MySQL 与 Redis 已启动
#   redis-cli ping  →  PONG

cd quick-start
./mvnw spring-boot:run
```

启动日志中可关注：

```
知识库加载完成：新增 N 个分块，当前共 N 个分块
RAG 已启用：QuestionAnswerAdvisor 挂载成功（topK=4, similarityThreshold=0.5）
```

### 4. 启动前端

```bash
cd quick-start-web
npm install
npm run dev
# 打开 http://localhost:5173
```

## API 一览

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 对话（ChatClient 隐式工具循环） |
| GET | `/api/chat?message=&chatId=` | 同上，便于调试 |
| GET | `/api/chat/conversations` | 会话列表（含标题、消息数、最近活跃） |
| GET | `/api/chat/history?chatId=` | 某会话历史消息 |
| DELETE | `/api/chat?chatId=` | 清空某会话记忆 |

### Agent（显式决策循环）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/chat` | 运行 Agent，返回 `answer` + `steps`（决策步骤） |
| GET | `/api/agent/chat?message=&chatId=` | 同上，便于调试 |

响应示例：

```json
{
  "answer": "厦门今天晴，28℃……推荐「XX咖啡馆」……",
  "steps": [
    { "step": 1, "toolName": "searchWeather", "arguments": "{\"city\":\"厦门\"}" },
    { "step": 2, "toolName": "searchPoi",     "arguments": "{\"keyword\":\"咖啡馆\"}" }
  ],
  "stepsUsed": 2,
  "completed": true,
  "maxSteps": 8,
  "note": null
}
```

### RAG 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/documents` | 灌入文本（自动分块向量化） |
| POST | `/api/rag/documents/file` | 上传 txt/md 文件入库 |
| GET | `/api/rag/count` | 当前向量库分块数 |
| DELETE | `/api/rag/documents` | 清空向量库 |

### 图片分析

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/image/analyze` | 上传图片分析（multipart） |
| POST | `/api/image/analyze-url` | 按 URL 分析图片 |

### 单词推送

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/words/generate?count=&level=` | AI 生成单词并推送微信 |

## 项目结构

```
study-test-ai/
├── quick-start/                          # 主应用
│   └── src/main/
│       ├── java/com/fanzhuo/quickstart/
│       │   ├── config/                   # 配置：ChatClient / 模型 / 向量库 / 知识库初始化
│       │   ├── web/
│       │   │   ├── agent/                # 显式决策循环（AgentLoopService）
│       │   │   ├── controller/           # HTTP 接口层
│       │   │   ├── service/              # 业务编排层
│       │   │   ├── memory/               # MySQL 会话记忆
│       │   │   ├── tool/                 # @Tool 工具集
│       │   │   ├── prompt/               # 系统提示词（代码常量）
│       │   │   ├── push/                 # 微信推送
│       │   │   └── demo/                 # 学习用示例（默认停用）
│       │   └── resources/
│       │       ├── knowledge/            # RAG 内置知识库（启动自动加载）
│       │       ├── application.properties
│       │       └── schema.sql            # 建表脚本（启动自动执行）
├── mcp-server/                           # 独立 MCP 服务器模块
└── quick-start-web/                      # Vue 3 前端
    └── src/
        ├── components/                   # Sidebar / MessageItem / ChatInput
        ├── api/                          # 后端接口封装
        └── utils/markdown.js             # Markdown 渲染
```

## 关键设计说明

### 为什么自己实现 `MysqlChatMemory`

Spring AI 官方 `MessageWindowChatMemory` 是**内存实现**，重启即丢。本项目直接实现 `ChatMemory` 接口：

- **写**：追加写（`msg_order` 递增），完整历史保留，便于审计
- **读**：只返回最近 `windowSize` 条（可配），避免上下文无限膨胀
- **附加**：首次提问自动作为会话标题

### RAG 知识库的两个来源

| 来源 | 时机 | 适用 |
|------|------|------|
| `resources/knowledge/*.md` | 启动自动加载 | 静态基线知识（话术、FAQ） |
| `/api/rag/*` 接口 | 运行时 | 动态补充、临时资料 |

启动加载带**内容指纹幂等**：文件未变动则跳过向量化（秒启动、零 API 调用）；变动才重建。

### 向量库为何继承 `SimpleVectorStore`

`SimpleVectorStore` 的底层存储字段 `store` 是 `protected` 且无公开 `size()`，继承后可直接访问，从而补上「容量查询」与「清空重建」能力（`/api/rag/count` 与知识库重建依赖）。

写操作通过重写 `doAdd`/`doDelete` 模板方法拦截，**所有写入路径统一自动落盘**。

> 注意：`SimpleVectorStore` 官方定位为「测试/演示用」，检索为全量暴力余弦计算。文档量上万时建议替换为 PGVector / RedisVectorStore。

## 注意事项

### 密钥管理

- 所有密钥存放在 `application-secret.properties`，该文件已被 `.gitignore` 排除
- 提交前请用 `git status` 确认没有敏感文件进入暂存区
- `application-secret.properties.example` 只含占位符，可安全提交

### 必需的外部服务

| 服务 | 缺失后果 |
|------|----------|
| MySQL | 应用启动失败（会话记忆依赖） |
| Redis | 应用启动失败（Redisson 在 Bean 创建时即连接） |
| DeepSeek API | 对话不可用 |
| 百炼 API | `EmbeddingModelConfig` 找不到配置导致启动失败 |

可选服务（图片识别、图片搜索、天气、微信推送）缺失时仅对应工具不可用。

## 已知限制与后续方向

- **无鉴权**：接口当前完全开放，生产环境需增加认证
- **无流式输出**：前端打字机为本地模拟，可改造为 SSE 真流式
- **向量库单机**：JSON 快照为本地文件，多实例部署需换分布式向量库
- **测试覆盖薄**：目前仅有上下文加载测试

## License

本项目仅用于学习与技术交流。
