<template>
  <div :class="['msg-row', message.role]">
    <div v-if="message.role === 'ai'" class="avatar">AI</div>
    <div class="msg-body">
      <img
        v-if="message.image"
        class="msg-image"
        :src="message.image"
        :alt="message.imageName || '图片'"
      />
      <div v-if="message.role === 'user' && message.content" class="user-bubble">
        {{ message.content }}
      </div>
      <div
        v-else-if="message.role === 'ai'"
        :class="['md-body', { streaming }]"
        v-html="rendered"
        @click="onBodyClick"
      ></div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  message: { type: Object, required: true },
  streaming: { type: Boolean, default: false }
})

const rendered = computed(() => renderMarkdown(props.message.content))

// 事件委托处理代码块复制按钮
function onBodyClick(e) {
  const btn = e.target.closest('.code-copy')
  if (!btn) return
  const code = btn.closest('.code-block')?.querySelector('code')
  if (!code) return
  navigator.clipboard.writeText(code.innerText).then(() => {
    btn.textContent = '已复制'
    setTimeout(() => {
      btn.textContent = '复制'
    }, 1500)
  })
}
</script>

<style scoped>
.msg-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  animation: msg-in 0.25s ease;
}
.msg-row.user { justify-content: flex-end; }

.msg-body {
  max-width: 80%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.msg-row.user .msg-body { align-items: flex-end; }
.msg-row.ai .msg-body {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 4px 16px 16px 16px;
  padding: 12px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.05);
}

.user-bubble {
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  color: #fff;
  padding: 10px 14px;
  border-radius: 16px 16px 4px 16px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14.5px;
}

.msg-image {
  max-width: 280px;
  max-height: 220px;
  border-radius: 10px;
  border: 1px solid var(--border);
  object-fit: cover;
}
</style>
