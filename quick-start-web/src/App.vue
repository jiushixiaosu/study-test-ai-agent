<template>
  <div class="layout">
    <Sidebar
      :chats="chats"
      :current-chat-id="currentChatId"
      @new="newChat"
      @refresh="loadConversations"
      @switch="switchChat"
      @remove="removeChat"
    />

    <!-- 右侧对话区 -->
    <main class="chat-main">
      <header class="chat-header">{{ currentTitle }}</header>

      <div class="messages" ref="msgBox">
        <div class="messages-inner">
          <MessageItem
            v-for="(m, i) in currentMessages"
            :key="i"
            :message="m"
            :streaming="typing && i === currentMessages.length - 1"
          />
          <div v-if="loading && !typing" class="typing-row">
            <div class="avatar">AI</div>
            <div class="typing-dots"><span /><span /><span /></div>
          </div>
        </div>
      </div>

      <footer class="input-area">
        <ChatInput :loading="loading" @send="send" @send-image="sendImage" />
      </footer>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import Sidebar from './components/Sidebar.vue'
import MessageItem from './components/MessageItem.vue'
import ChatInput from './components/ChatInput.vue'
import { sendChat, listConversations, getHistory, clearConversation, analyzeImage } from './api/chat'

let seq = 0
const genId = () => `chat-${Date.now()}-${seq++}`

const chats = ref([{ id: genId(), title: '新对话', messages: [] }])
const currentChatId = ref(chats.value[0].id)
const loading = ref(false)
const typing = ref(false) // 打字机输出中
const msgBox = ref(null)

const currentChat = computed(
  () => chats.value.find((c) => c.id === currentChatId.value) || chats.value[0]
)
const currentMessages = computed(() => currentChat.value.messages)
const currentTitle = computed(() => currentChat.value.title)

function scrollToBottom() {
  nextTick(() => {
    if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
  })
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 打字机效果：把完整回复逐步填充到消息里
async function typewrite(target, fullText) {
  typing.value = true
  target.content = ''
  // 按总长度自适应步长，最多约 240 帧
  const step = Math.max(1, Math.ceil(fullText.length / 240))
  try {
    for (let i = 0; i < fullText.length; i += step) {
      target.content = fullText.slice(0, i + step)
      scrollToBottom()
      await sleep(16)
    }
  } finally {
    target.content = fullText
    typing.value = false
    scrollToBottom()
  }
}

// 首屏：从后端 MySQL 拉取已有会话列表
onMounted(loadConversations)

async function loadConversations() {
  try {
    const list = await listConversations()
    if (!list || !list.length) return
    chats.value = list.map((c) => {
      const raw = c.title && c.title.trim() ? c.title : c.conversationId
      const title = raw.length > 20 ? raw.slice(0, 20) + '…' : raw
      return { id: c.conversationId, title, messages: [] } // 历史按需点开时再加载
    })
    if (!chats.value.find((c) => c.id === currentChatId.value)) {
      currentChatId.value = chats.value[0].id
    }
  } catch (e) {
    // 后端未启动或不可用时，保留本地空会话，不影响新建对话
    console.warn('加载会话列表失败，使用本地会话', e)
  }
}

async function send(text) {
  if (!text || loading.value) return

  // 首条消息用作会话标题
  if (currentMessages.value.length === 0) {
    currentChat.value.title = text.slice(0, 12)
  }

  currentMessages.value.push({ role: 'user', content: text })
  loading.value = true
  scrollToBottom()

  try {
    const reply = await sendChat(text, currentChatId.value)
    // 必须用 reactive 包装：否则 typewrite 改的是原始对象，绕过 Vue 代理，
    // computed 不会重算，界面会卡在打字机中途的某一帧
    const msg = reactive({ role: 'ai', content: '' })
    currentMessages.value.push(msg)
    await typewrite(msg, reply)
  } catch (e) {
    currentMessages.value.push({
      role: 'ai',
      content: '请求失败：' + (e.message || '未知错误')
    })
  } finally {
    loading.value = false
  }
}

// 上传图片 -> 把「输入框提示词 + 图片」一起提交给视觉模型 -> 结果作为 AI 消息插入
async function sendImage({ file, prompt, preview }) {
  if (loading.value) return
  const question = prompt || '这是一张聊天记录截图，请帮我提取并整理其中的对话内容'

  if (currentMessages.value.length === 0) {
    currentChat.value.title = (prompt || file.name).slice(0, 12)
  }
  currentMessages.value.push({
    role: 'user',
    content: prompt,
    image: preview,
    imageName: file.name
  })
  loading.value = true
  scrollToBottom()

  try {
    const res = await analyzeImage(file, question, currentChatId.value)
    // 同 send：用 reactive 包装以保留响应式
    const msg = reactive({ role: 'ai', content: '' })
    currentMessages.value.push(msg)
    await typewrite(msg, res.content)
  } catch (err) {
    currentMessages.value.push({
      role: 'ai',
      content: '图片分析失败：' + (err.message || '未知错误')
    })
  } finally {
    loading.value = false
  }
}

function newChat() {
  const c = { id: genId(), title: '新对话', messages: [] }
  chats.value.unshift(c)
  currentChatId.value = c.id
}

async function switchChat(id) {
  currentChatId.value = id
  // 若该会话尚未加载历史（或为空），从后端拉取
  const chat = chats.value.find((c) => c.id === id)
  if (chat && chat.messages.length === 0) {
    try {
      const history = await getHistory(id)
      chat.messages = history.map((m) => ({
        role: m.messageType === 'USER' ? 'user' : 'ai',
        content: m.text
      }))
    } catch (e) {
      console.warn('加载历史失败', e)
    }
  }
  scrollToBottom()
}

async function removeChat(id) {
  try {
    await clearConversation(id) // 调用后端 DELETE /api/chat?chatId= 清空 MySQL 中该会话
  } catch (e) {
    alert('清空会话失败：' + (e.message || '未知错误'))
    return
  }
  const idx = chats.value.findIndex((c) => c.id === id)
  if (idx !== -1) {
    chats.value.splice(idx, 1)
  }
  if (currentChatId.value === id) {
    currentChatId.value = chats.value[0]?.id || ''
    if (!chats.value.length) newChat()
  }
}
</script>

<style scoped>
.layout {
  display: flex;
  height: 100%;
}

.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chat-header {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  font-size: 15px;
}

.messages {
  flex: 1;
  overflow-y: auto;
}

.messages-inner {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 24px 12px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.typing-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  animation: msg-in 0.25s ease;
}

.typing-dots {
  display: flex;
  gap: 5px;
  align-items: center;
  background: #fff;
  border: 1px solid var(--border);
  padding: 15px 16px;
  border-radius: 4px 16px 16px 16px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.05);
}
.typing-dots span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #b9bec7;
  animation: dot-bounce 1.2s infinite;
}
.typing-dots span:nth-child(2) { animation-delay: 0.15s; }
.typing-dots span:nth-child(3) { animation-delay: 0.3s; }

.input-area {
  padding: 6px 24px 20px;
}
.input-area > * {
  max-width: 820px;
  margin: 0 auto;
}
</style>
