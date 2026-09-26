// 时间戳格式化：返回 "HH:MM" 或 "MM-DD" 或 "YYYY-MM-DD"
export function formatTime(timestamp) {
  if (!timestamp) return ''
  const date = new Date(timestamp * 1000)
  const now = new Date()
  const pad = n => (n < 10 ? '0' + n : '' + n)

  const isToday = date.toDateString() === now.toDateString()
  if (isToday) {
    return pad(date.getHours()) + ':' + pad(date.getMinutes())
  }

  const yest = new Date(now.getTime() - 86400000)
  if (date.toDateString() === yest.toDateString()) return '昨天'

  const diffDays = (now - date) / 86400000
  if (diffDays < 7) {
    const wd = ['日', '一', '二', '三', '四', '五', '六'][date.getDay()]
    return '周' + wd
  }

  if (date.getFullYear() === now.getFullYear()) {
    return pad(date.getMonth() + 1) + '-' + pad(date.getDate())
  }
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
}

// 文本截断
export function truncate(text, max = 30) {
  if (!text) return ''
  return text.length > max ? text.slice(0, max) + '…' : text
}

/**
 * 消息正文（emoji-only 策略）
 *
 * 优先级：文本 → 贴纸 emoji → 媒体类型占位 → "[消息]"
 * 注意：不下载、不解码、不缓存任何贴纸/图片资源。
 * 手环端只保存短字符串，重处理全部放在手机端完成。
 */
export function messageText(message, max = 200) {
  if (!message) return ''
  const text = message.text || ''
  if (text) return truncate(text, max)
  // 贴纸只显示 emoji（Android 端只传 sticker.emoji）
  if (message.sticker && message.sticker.emoji) return message.sticker.emoji
  const media = message.media
  if (media && media.type) {
    switch (media.type) {
      case 'sticker': return media.emoji || '[贴纸]'
      case 'photo': return '[图片]'
      case 'voice': return '[语音]'
      case 'video': return '[视频]'
      case 'video_note': return '[视频消息]'
      case 'gif': return '[动图]'
      case 'audio': return media.title ? '[音乐] ' + media.title : '[音乐]'
      case 'document': return media.name ? '[文件] ' + media.name : '[文件]'
      case 'location': return '[位置]'
      case 'contact': return '[联系人]'
      case 'poll': return '[投票] ' + (media.question || '')
      case 'webpage': return '[链接] ' + (media.title || media.site || '')
      default: return '[媒体]'
    }
  }
  return '[消息]'
}

// 会话列表预览（更短）
export function messagePreview(message) {
  if (!message) return ''
  return messageText(message, 24)
}

// 当前时间字符串（用于 header 时间显示）
export function nowTimeString() {
  const date = new Date()
  const pad = n => (n < 10 ? '0' + n : '' + n)
  return pad(date.getHours()) + ':' + pad(date.getMinutes())
}

// 首字母大写（用于头像占位）
export function initial(name) {
  if (!name) return '?'
  return name.charAt(0).toUpperCase()
}

// 字数统计
export function charCount(text, max = 200) {
  return (text || '').length + ' / ' + max
}
