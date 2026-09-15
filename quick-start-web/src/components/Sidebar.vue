<template>
  <aside class="sidebar">
    <div class="sidebar-top">
      <button class="new-chat" @click="$emit('new')">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"
             stroke-width="2.2" stroke-linecap="round">
          <path d="M12 5v14M5 12h14" />
        </svg>
        新建对话
      </button>
      <button class="refresh" title="刷新会话列表" @click="$emit('refresh')">↻</button>
    </div>

    <ul class="chat-list">
      <li
        v-for="c in chats"
        :key="c.id"
        :class="['chat-item', { active: c.id === currentChatId }]"
        @click="$emit('switch', c.id)"
      >
        <span class="chat-title">{{ c.title }}</span>
        <span class="chat-del" title="清空会话" @click.stop="$emit('remove', c.id)">×</span>
      </li>
    </ul>

    <div class="sidebar-footer">Powered by Spring AI</div>
  </aside>
</template>

<script setup>
defineProps({
  chats: { type: Array, required: true },
  currentChatId: { type: String, default: '' }
})
defineEmits(['new', 'refresh', 'switch', 'remove'])
</script>

<style scoped>
.sidebar {
  width: 256px;
  flex-shrink: 0;
  background: linear-gradient(180deg, #14151a, #1b1d24);
  color: #e8eaf0;
  display: flex;
  flex-direction: column;
  padding: 14px 12px;
  gap: 12px;
}

.sidebar-top {
  display: flex;
  gap: 8px;
}

.new-chat {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  border: none;
  color: #fff;
  padding: 11px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: filter 0.15s;
}
.new-chat:hover { filter: brightness(1.12); }

.refresh {
  width: 40px;
  background: rgba(255, 255, 255, 0.06);
  color: #cfd3dc;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 10px;
  cursor: pointer;
  font-size: 15px;
  transition: background 0.15s;
}
.refresh:hover { background: rgba(255, 255, 255, 0.12); }

.chat-list {
  list-style: none;
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.chat-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 11px;
  border-radius: 9px;
  cursor: pointer;
  font-size: 13.5px;
  color: #c8ccd6;
  transition: background 0.15s;
}
.chat-item:hover { background: rgba(255, 255, 255, 0.06); }
.chat-item.active {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  box-shadow: inset 2px 0 0 var(--accent);
}

.chat-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-del {
  color: #9aa0ad;
  padding-left: 6px;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}
.chat-item:hover .chat-del { opacity: 1; }
.chat-del:hover { color: #ff6b6b; }

.sidebar-footer {
  font-size: 12px;
  color: #6b7280;
  text-align: center;
  padding-top: 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
</style>
