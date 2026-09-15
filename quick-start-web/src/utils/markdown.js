import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>`
      } catch (_) {
        // 高亮失败时按纯文本处理
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  }
})

const defaultFence = md.renderer.rules.fence

// 给代码块包一层带语言标签 + 复制按钮的头部
md.renderer.rules.fence = (...args) => {
  const lang = (args[0][args[1]].info || '').trim()
  const label = lang ? `<span class="code-lang">${lang}</span>` : '<span></span>'
  return (
    `<div class="code-block"><div class="code-header">${label}` +
    `<button type="button" class="code-copy">复制</button></div>` +
    `${defaultFence(...args)}</div>`
  )
}

export function renderMarkdown(text) {
  return md.render(text || '')
}
