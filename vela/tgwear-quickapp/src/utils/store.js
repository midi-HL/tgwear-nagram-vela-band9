/*
 * One bounded app-owned in-memory store shared by all Vela page bundles.
 * Private message bodies are intentionally not written to persistent storage.
 */
const root = typeof globalThis !== 'undefined' ? globalThis : global
const ROOT_KEY = '__tgWearStoreV2'
const MAX_MESSAGES_PER_PEER = 80
const MAX_CACHED_PEERS = 8
const MAX_DIALOGS = 100

function makeState() {
  return {
    session: null,
    dialogs: [],
    dialogsDirty: true,
    messages: Object.create(null),
    connectionState: 2,
    settings: { fontScale: 1.0, vibration: true, keepScreenOn: false },
    connectionStatus: null,
    _settingsLoaded: false
  }
}
if (!root[ROOT_KEY]) root[ROOT_KEY] = makeState()
const state = root[ROOT_KEY]
let appFacade = null

function messageId(message) {
  return message && (message.id !== undefined && message.id !== null ? String(message.id) : '')
}

function normalizeMessage(message) {
  if (!message || typeof message !== 'object') return null
  const text = typeof message.text === 'string' ? message.text : ''
  return {
    id: message.id,
    out: !!message.out,
    sender: typeof message.sender === 'string' ? message.sender.slice(0, 64) : '',
    text: text.slice(0, 2000),
    date: Number(message.date) || 0,
    media: message.media ? { type: String(message.media.type || '').slice(0, 24), title: String(message.media.title || '').slice(0, 80) } : undefined,
    sending: !!message.sending,
    failed: !!message.failed,
    operation_id: typeof message.operation_id === 'string' ? message.operation_id.slice(0, 96) : undefined,
    reply_to: message.reply_to && typeof message.reply_to === 'object' ? { id: message.reply_to.id } : undefined
  }
}

function trimMessages(list) {
  if (list.length <= MAX_MESSAGES_PER_PEER) return list
  return list.slice(list.length - MAX_MESSAGES_PER_PEER)
}

function touchPeer(peer) {
  const keys = Object.keys(state.messages)
  if (keys.indexOf(peer) >= 0) return
  while (Object.keys(state.messages).length >= MAX_CACHED_PEERS) {
    const oldest = Object.keys(state.messages)[0]
    delete state.messages[oldest]
  }
}

const store = {
  attachApp(facade) {
    if (!facade || typeof facade.bridgeStoreGet !== 'function') return false
    appFacade = facade
    return true
  },
  get(key) { return appFacade ? appFacade.bridgeStoreGet(key) : state[key] },
  set(key, value) { return appFacade ? appFacade.bridgeStoreSet(key, value) : (state[key] = value) },

  getDialog(peer) {
    if (appFacade) return appFacade.bridgeStoreGetDialog(peer)
    const list = state.messages[peer]
    return list || []
  },

  appendDialog(peer, messages, prepend = false) {
    if (appFacade) return appFacade.bridgeStoreAppendDialog(peer, messages, prepend)
    if (!peer || !Array.isArray(messages)) return []
    touchPeer(peer)
    const current = state.messages[peer] || []
    const incoming = messages.map(normalizeMessage).filter(Boolean)
    const merged = prepend ? incoming.concat(current) : current.concat(incoming)
    const seen = Object.create(null)
    const unique = []
    merged.forEach(message => {
      const id = messageId(message)
      if (id && seen[id]) return
      if (id) seen[id] = true
      unique.push(message)
    })
    unique.sort((a, b) => (Number(a.date) - Number(b.date)) || (Number(a.id) - Number(b.id)))
    state.messages[peer] = trimMessages(unique)
    return state.messages[peer]
  },

  upsertMessage(peer, message) {
    if (appFacade) return appFacade.bridgeStoreUpsertMessage(peer, message)
    if (!peer || !message) return
    touchPeer(peer)
    const normalized = normalizeMessage(message)
    if (!normalized) return
    const list = state.messages[peer] || []
    const key = messageId(normalized)
    const idx = key ? list.findIndex(item => messageId(item) === key) : -1
    if (idx >= 0) list[idx] = normalized
    else list.push(normalized)
    list.sort((a, b) => (Number(a.date) - Number(b.date)) || (Number(a.id) - Number(b.id)))
    state.messages[peer] = trimMessages(list)
  },

  replaceMessageId(peer, localId, message, operationId) {
    if (appFacade) return appFacade.bridgeStoreReplaceMessageId(peer, localId, message, operationId)
    if (!peer) return false
    const list = state.messages[peer] || []
    const idx = list.findIndex(item => String(item.id) === String(localId)
      || (!!operationId && item.operation_id === operationId))
    if (idx < 0) {
      if (message) this.upsertMessage(peer, message)
      return false
    }
    const current = list[idx]
    list[idx] = normalizeMessage(message || Object.assign({}, current, { id: localId, sending: false, failed: false }))
    list.sort((a, b) => (Number(a.date) - Number(b.date)) || (Number(a.id) - Number(b.id)))
    return true
  },

  removeMessages(peer, ids) {
    if (appFacade) return appFacade.bridgeStoreRemoveMessages(peer, ids)
    const targets = Array.isArray(ids) ? ids.map(String) : []
    if (!targets.length) return
    const peers = peer ? [peer] : Object.keys(state.messages)
    peers.forEach(key => {
      state.messages[key] = (state.messages[key] || []).filter(item => targets.indexOf(String(item.id)) < 0)
    })
  },

  upsertDialog(dialog) {
    if (appFacade) return appFacade.bridgeStoreUpsertDialog(dialog)
    if (!dialog || !dialog.peer) return
    const list = state.dialogs
    const idx = list.findIndex(item => item.peer === dialog.peer)
    const normalized = Object.assign({}, dialog, {
      title: String(dialog.title || '未知').slice(0, 80),
      last_message: normalizeMessage(dialog.last_message)
    })
    if (idx >= 0) list[idx] = normalized
    else list.unshift(normalized)
    list.sort((a, b) => (Number(b.last_message_date) - Number(a.last_message_date)) || (Number(b.last_message_id) - Number(a.last_message_id)))
    if (list.length > MAX_DIALOGS) list.length = MAX_DIALOGS
  },

  clearDialogs() { if (appFacade) return appFacade.bridgeStoreClearDialogs(); state.dialogs = [] },
  clearSession() { if (appFacade) return appFacade.bridgeStoreClearSession(); state.session = null },

  loadSettings() {
    if (appFacade) return Promise.resolve(appFacade.bridgeStoreLoadSettings())
    if (state._settingsLoaded) return Promise.resolve(state.settings)
    state._settingsLoaded = true
    try {
      const storage = require('@system.storage')
      return new Promise(resolve => {
        storage.get({
          key: 'settings',
          success: result => {
            try { Object.assign(state.settings, JSON.parse((result && result.value) || result || '{}')) } catch (e) {}
            resolve(state.settings)
          },
          fail: () => resolve(state.settings)
        })
      })
    } catch (e) {
      return Promise.resolve(state.settings)
    }
  },

  saveSettings() {
    if (appFacade) return appFacade.bridgeStoreSaveSettings()
    try {
      const storage = require('@system.storage')
      storage.set({ key: 'settings', value: JSON.stringify(state.settings) })
    } catch (e) { console.warn('[store] settings persistence unavailable') }
  },

  snapshot() {
    if (appFacade) return appFacade.bridgeStoreSnapshot()
    let messageCount = 0
    Object.keys(state.messages).forEach(peer => { messageCount += state.messages[peer].length })
    return { dialogs: state.dialogs.length, peers: Object.keys(state.messages).length, messages: messageCount, maxDialogs: MAX_DIALOGS, maxPerPeer: MAX_MESSAGES_PER_PEER }
  },

  resetForTests() {
    const fresh = makeState()
    Object.keys(state).forEach(key => { delete state[key] })
    Object.assign(state, fresh)
  }
}

export default store
