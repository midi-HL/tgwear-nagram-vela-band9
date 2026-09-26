import interconn from './interconn'

const root = typeof globalThis !== 'undefined' ? globalThis : global
const ROOT_KEY = '__tgWearApiV2'
if (!root[ROOT_KEY]) {
  root[ROOT_KEY] = {
    sequence: 0,
    pending: Object.create(null),
    listeners: Object.create(null),
    facade: null,
    removeTransportListener: null,
    timeoutMs: 20000,
    retryIntervalMs: 5000,
    maxPending: 32
  }
}
const core = root[ROOT_KEY]

function failAll(error) {
  Object.keys(core.pending).forEach(id => {
    const item = core.pending[id]
    clearTimeout(item.timer)
    clearInterval(item.retryTimer)
    delete core.pending[id]
    item.reject(error)
  })
}

function emit(event, data) {
  const handlers = (core.listeners[event] || []).slice()
  handlers.forEach(handler => {
    try { handler(data) } catch (e) { console.warn('[api] event handler failed:', event) }
  })
}

function receive(payload) {
  if (!payload || typeof payload !== 'object') return
  if (payload.id !== undefined && payload.__event !== true) {
    const id = String(payload.id)
    const item = core.pending[id]
    if (!item) return
    clearTimeout(item.timer)
    clearInterval(item.retryTimer)
    delete core.pending[id]
    if (payload.error) item.reject(payload.error)
    else item.resolve(payload.result)
    return
  }
  if (payload.__event === true && typeof payload.event === 'string') emit(payload.event, payload.data)
}

function ensureTransportListener() {
  if (core.removeTransportListener) return
  core.removeTransportListener = interconn.onMessage(receive)
  interconn.onState((state, info) => {
    if (state !== 1) failAll({ code: -1006, msg: 'wearable session disconnected', detail: info })
  })
}

function call(method, params = {}, options = {}) {
  if (core.facade) return core.facade.bridgeCall(method, params, options)
  ensureTransportListener()
  interconn.init()
  const id = 'v2-' + Date.now().toString(36) + '-' + (++core.sequence).toString(36)
  const request = { __rpc: true, id, method, params }
  if (options.operationId) request.operation_id = options.operationId
  if (options.peer) request.peer = options.peer
  return new Promise((resolve, reject) => {
    if (Object.keys(core.pending).length >= core.maxPending) {
      reject({ code: -2, msg: 'too many pending TG Wear requests' })
      return
    }
    const timeoutMs = Number.isFinite(options.timeoutMs) ? Math.max(1000, Math.min(60000, options.timeoutMs)) : core.timeoutMs
    const timer = setTimeout(() => {
      if (!core.pending[id]) return
      const item = core.pending[id]
      delete core.pending[id]
      clearInterval(item.retryTimer)
      reject({ code: -1, msg: 'RPC timeout: ' + method, requestId: id })
    }, timeoutMs)
    let retries = 0
    const retryTimer = setInterval(() => {
      if (!core.pending[id] || retries >= 3) return
      retries++
      interconn.send(request).catch(error => {
        if (core.pending[id]) console.warn('[api] idempotent RPC resend failed:', method, error && error.msg)
      })
    }, core.retryIntervalMs)
    core.pending[id] = { resolve, reject, timer, retryTimer, method, operationId: options.operationId || '' }
    interconn.send(request).catch(error => {
      const item = core.pending[id]
      if (!item) return
      clearTimeout(item.timer)
      clearInterval(item.retryTimer)
      delete core.pending[id]
      reject(error)
    })
  })
}

const api = {
  attachApp(facade) {
    if (!facade || typeof facade.bridgeCall !== 'function') return false
    core.facade = facade
    interconn.attachApp(facade)
    return true
  },
  initializeRoot() {
    ensureTransportListener()
    interconn.init()
    return true
  },
  _call(method, params = {}, options = {}) { return call(method, params, options) },
  on(event, handler) {
    if (core.facade) return core.facade.bridgeOn(event, handler)
    if (!event || typeof handler !== 'function') return () => {}
    const list = core.listeners[event] || (core.listeners[event] = [])
    if (list.indexOf(handler) < 0) list.push(handler)
    return () => { core.listeners[event] = (core.listeners[event] || []).filter(item => item !== handler) }
  },
  _emit: emit,
  _receive: receive,
  snapshot() {
    return { pending: Object.keys(core.pending).length, listeners: Object.keys(core.listeners).reduce((n, key) => n + core.listeners[key].length, 0) }
  },
  resetForTests() {
    failAll({ code: -1, msg: 'reset' })
    core.sequence = 0
    core.listeners = Object.create(null)
  },

  // Dialogs
  getDialogs(limit = 30, cursor = null, folderId = 0) { return call('dialogs.get', { limit, cursor, folder_id: folderId }) },
  pinDialog(peer, pin = true) { return call('dialogs.pin', { peer, pin }) },
  muteDialog(peer, seconds = 0) { return call('dialogs.mute', { peer, seconds }) },
  archiveDialog(peer, archive = true) { return call('dialogs.archive', { peer, archive }) },
  deleteDialog(peer, onlyHistory = false, operationId) { return call('dialogs.delete', { peer, only_history: onlyHistory }, { operationId, peer }) },

  // Messages
  getHistory(peer, limit = 30, offsetId = 0) { return call('messages.getHistory', { peer, limit, offset_id: offsetId }, { peer }) },
  sendText(peer, text, replyTo = 0, operationId) {
    const op = operationId || ('op-' + Date.now().toString(36) + '-' + (++core.sequence).toString(36))
    return call('messages.sendText', { peer, text, reply_to: replyTo, operation_id: op }, { operationId: op, peer })
  },
  reply(peer, text, replyTo, operationId) {
    const op = operationId || ('op-' + Date.now().toString(36) + '-' + (++core.sequence).toString(36))
    return call('messages.reply', { peer, text, reply_to: replyTo, operation_id: op }, { operationId: op, peer })
  },
  editMessage(peer, messageId, text, operationId) { return call('messages.edit', { peer, message_id: messageId, text, operation_id: operationId }, { operationId, peer }) },
  deleteMessage(peer, messageId, revoke = true, operationId) { return call('messages.delete', { peer, message_id: messageId, revoke, operation_id: operationId }, { operationId, peer }) },
  forwardMessage(peer, messageId, toPeer, operationId) { return call('messages.forward', { peer, message_id: messageId, to_peer: toPeer, operation_id: operationId }, { operationId, peer }) },
  searchMessages(peer, query, limit = 20) { return call('messages.search', { peer, query, limit }, { peer }) },
  setTyping(peer, action = 0) { return call('messages.setTyping', { peer, action }, { peer, timeoutMs: 5000 }) },
  markRead(peer, messageId) { return call('messages.markRead', { peer, max_id: messageId }, { peer, timeoutMs: 5000 }) },

  // Peers / auth / diagnostics
  getPeer(peer) { return call('peers.get', { peer }, { peer }) },
  pinMessage(peer, messageId, unpin = false, operationId) { return call('peers.pinMessage', { peer, message_id: messageId, unpin, operation_id: operationId }, { operationId, peer }) },
  getAuthState() { return call('auth.getState') },
  getBridgeStatus() { return call('bridge.getStatus') },
  acknowledgePhoneProbe(nonce) { return call('bridge.probeAck', { nonce }) },
  logout() { return call('auth.logout') }
}

export default api
