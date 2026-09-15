# quick-start-web（Vue3 前端）

独立的 Vue3 + Vite 聊天界面，调用后端 `POST /api/chat`。

## 启动

```bash
# 1. 先启动后端（quick-start 模块，端口 8080）
cd ../quick-start
./mvnw spring-boot:run

# 2. 另开终端启动前端
npm install
npm run dev
# 浏览器打开 http://localhost:5173
```

## 说明

- 前端 dev server 在 5173，通过 Vite 代理把 `/api` 转发到后端 8080（同时后端也开了 CORS）。
- 每个会话 tab 自动生成独立 `chatId`，后端按 `chatId` 复用记忆实现多轮对话（内存版，重启丢失）。
- 暂不启用流式输出，AI 回复为一次性返回。
- 后续可升级：流式输出（SSE）、会话历史持久化（接 MySQL 后从后端拉取历史）。
