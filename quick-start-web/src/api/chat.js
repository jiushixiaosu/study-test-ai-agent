import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

/**
 * 发送一条对话消息。
 * @param {string} message 用户消息
 * @param {string} chatId  会话标识，相同 chatId 复用后端记忆实现多轮对话
 * @returns {Promise<string>} AI 回复文本
 */
export function sendChat(message, chatId) {
  return http
    .post('/chat', { message, chatId })
    .then((res) => res.data)
}

/**
 * 列出所有会话（来自后端 MySQL）。
 * @returns {Promise<Array<{conversationId:string,msgCount:number,lastActive:string}>>}
 */
export function listConversations() {
  return http.get('/chat/conversations').then((res) => res.data)
}

/**
 * 获取某会话的历史消息（按窗口裁剪，与喂给模型的一致）。
 * @param {string} chatId
 * @returns {Promise<Array<{messageType:string,text:string}>>}
 */
export function getHistory(chatId) {
  return http.get('/chat/history', { params: { chatId } }).then((res) => res.data)
}

/**
 * 清空某会话（从后端 MySQL 删除）。
 * @param {string} chatId
 */
export function clearConversation(chatId) {
  return http.delete('/chat', { params: { chatId } }).then((res) => res.data)
}

/**
 * 上传图片并让视觉模型（GLM-4V-Flash）分析内容。
 * 典型场景：聊天记录截图、图表、验证码等。
 * @param {File} file 本地图片文件
 * @param {string} question 可选分析指令；留空则按"聊天记录截图"分析
 * @param {string} chatId 会话标识；传入后这段「提问+图片+分析结果」会持久化到会话记忆
 * @returns {Promise<{content:string}>}
 */
export function analyzeImage(file, question, chatId) {
  const formData = new FormData()
  formData.append('file', file)
  if (question) formData.append('question', question)
  if (chatId) formData.append('chatId', chatId)
  return http
    .post('/image/analyze', formData, { timeout: 120000 })
    .then((res) => res.data)
}
