<template>
  <div class="input-card">
    <div v-if="imagePreview" class="preview-strip">
      <div class="preview-item">
        <img :src="imagePreview" alt="图片预览" />
        <button class="preview-remove" title="移除图片" @click="clearImage">×</button>
      </div>
    </div>
    <div class="input-row">
      <button class="icon-btn" title="上传图片分析（聊天记录截图等）" @click="pickImage">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
             stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
          <circle cx="12" cy="13" r="4" />
        </svg>
      </button>
      <input ref="fileInput" type="file" accept="image/*" hidden @change="onImageSelected" />
      <textarea
        ref="area"
        v-model="text"
        rows="1"
        placeholder="输入消息，Enter 发送，Shift+Enter 换行"
        @input="autoGrow"
        @keydown.enter.exact.prevent="submit"
      ></textarea>
      <button
        class="send-btn"
        :disabled="loading || (!text.trim() && !imageFile)"
        title="发送"
        @click="submit"
      >
        <svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor">
          <path d="M21 3 3 11l7 2 2 7 9-17z" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'

const props = defineProps({
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['send', 'send-image'])

const text = ref('')
const imageFile = ref(null)
const imagePreview = ref('')
const fileInput = ref(null)
const area = ref(null)

function pickImage() {
  fileInput.value.click()
}

function onImageSelected(e) {
  const file = e.target.files[0]
  e.target.value = '' // 允许重复选择同一文件
  if (!file) return
  clearImage()
  imageFile.value = file
  imagePreview.value = URL.createObjectURL(file)
}

function clearImage() {
  if (imagePreview.value) URL.revokeObjectURL(imagePreview.value)
  imageFile.value = null
  imagePreview.value = ''
}

function autoGrow() {
  const el = area.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

async function submit() {
  if (props.loading) return
  const t = text.value.trim()
  if (!t && !imageFile.value) return
  if (imageFile.value) {
    // preview URL 转交父组件用于气泡展示，这里不 revoke
    emit('send-image', { file: imageFile.value, prompt: t, preview: imagePreview.value })
    imageFile.value = null
    imagePreview.value = ''
  } else {
    emit('send', t)
  }
  text.value = ''
  await nextTick()
  autoGrow()
}
</script>

<style scoped>
.input-card {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(16, 24, 40, 0.08);
  padding: 10px 12px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-card:focus-within {
  border-color: var(--accent);
  box-shadow: 0 4px 18px rgba(99, 102, 241, 0.15);
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.icon-btn {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #8a8f99;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.icon-btn:hover {
  background: #f2f3f5;
  color: var(--accent);
}

textarea {
  flex: 1;
  resize: none;
  border: none;
  outline: none;
  font-size: 14.5px;
  line-height: 1.5;
  padding: 8px 4px;
  max-height: 160px;
  font-family: inherit;
  background: transparent;
}

.send-btn {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border: none;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--accent), var(--accent-2));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.15s, opacity 0.15s;
}
.send-btn:hover:not(:disabled) { transform: scale(1.06); }
.send-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.preview-strip {
  display: flex;
  gap: 8px;
  padding: 2px 2px 10px;
}
.preview-item {
  position: relative;
  width: 64px;
  height: 64px;
}
.preview-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--border);
}
.preview-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: none;
  background: rgba(0, 0, 0, 0.65);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}
</style>
