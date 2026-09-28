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

      <!-- Agent 决策过程：用原生 details 实现折叠，默认收起 -->
      <details v-if="hasSteps" class="steps">
        <summary>思考了 {{ stepsCount }} 步</summary>
        <div class="steps-list">
          <div v-for="(s, i) in message.steps" :key="i" class="step-item">
            <span class="step-no">{{ s.step }}</span>
            <span class="step-tool">{{ s.toolName }}</span>
            <code class="step-args">{{ prettyArgs(s.arguments) }}</code>
          </div>
        </div>
      </details>

      <div v-if="message.role === 'user' && message.content" class="user-bubble">
        {{ message.content }}
      </div>
      <div
        v-else-if="message.role === 'ai'"
        :class="['md-body', { streaming }]"
        v-html="rendered"
        @click="onBodyClick"
      ></div>

      <!-- 未正常完成时的提示（如达到步数上限） -->
      <div v-if="message.note" class="msg-note">{{ message.note }}</div>
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

const hasSteps = computed(() => Array.isArray(props.message.steps) && props.message.steps.length > 0)
const stepsCount = computed(() => props.message.stepsUsed || props.message.steps?.length || 0)

// 把工具参数 JSON 转成易读的 key=value 形式，例如 {"city":"厦门"} → city=厦门
function prettyArgs(raw) {
  if (!raw) return ''
  try {
    const obj = JSON.parse(raw)
    const parts = Object.entries(obj).map(([k, v]) => {
      const val = typeof v === 'object' ? JSON.stringify(v) : v
      return `${k}=${val}`
    })
    return parts.length ? parts.join(', ') : raw
  } catch {
    return raw
  }
}

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

/* ===== Agent 决策过程 ===== */
.steps {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fafbfc;
  font-size: 12.5px;
  overflow: hidden;
}

.steps summary {
  padding: 6px 10px;
  cursor: pointer;
  color: #6b7280;
  user-select: none;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 4px;
}
.steps summary::-webkit-details-marker { display: none; }
.steps summary::before {
  content: '▸';
  font-size: 10px;
  transition: transform 0.15s ease;
}
.steps[open] summary::before {
  content: '▾';
}
.steps summary:hover { background: #f3f4f6; }

.steps-list {
  padding: 2px 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.step-no {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.step-tool {
  font-weight: 600;
  color: #374151;
  flex-shrink: 0;
}

.step-args {
  color: #6b7280;
  font-size: 12px;
  background: #f3f4f6;
  padding: 1px 6px;
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-note {
  font-size: 12.5px;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
  padding: 6px 10px;
}
</style>
