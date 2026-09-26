import interconnect from '@system.interconnect'
import protocol from './protocol'

const root = typeof globalThis !== 'undefined' ? globalThis : global
const KEY = '__tgWearInterconnectV2'
if (!root[KEY]) {
  root[KEY] = {
    conn: null,
    initialized: false,
    facade: null,
    readyState: 2,
    messageHandlers: [],
    stateHandlers: [],
    physicalHandlers: [],
    lastSystemState: null,
    helloRequestTimer: null
  }
}
const core = root[KEY]

function notify(list, args) {
  list.slice().forEach(fn => {
    try { fn.apply(null, args) } catch (e) { console.warn('[interconn] observer failed') }
  })
}

function dispatchPayload(text) {
  let payload
  try { payload = JSON.parse(text) } catch (e) {
    console.warn('[interconn] assembled TGW payload is not JSON')
    return
  }
  notify(core.messageHandlers, [payload])
}

function rawSend(frame) {
  if (!core.conn) return Promise.reject({ code: -1, msg: 'interconnect not initialized' })
  return new Promise((resolve, reject) => {
    core.conn.send({
      data: frame,
      success: resolve,
      fail: (err, code) => reject({ code, msg: (err && (err.data || err.msg)) || String(err || 'send failed') })
    })
  })
}

function init() {
  if (core.facade) return core.facade.bridgeInit()
  if (core.initialized) return core.conn
  core.conn = interconnect.instance()
  core.initialized = true
  protocol.bind(rawSend, dispatchPayload, (state, info) => {
    if (state === 'ready') core.readyState = 1
    if (state === 'transport-down' || state === 'error') core.readyState = 2
    notify(core.stateHandlers, [core.readyState, { protocolState: state, info, protocol: protocol.snapshot() }])
  })
  core.conn.onopen = data => {
    core.readyState = 2 // Xiaomi link is up; TGW/2 hello is still required.
    protocol.onPhysicalState(true, data)
    notify(core.physicalHandlers, [true, data])
    if (core.helloRequestTimer) clearTimeout(core.helloRequestTimer)
    // Android is session authority. Request a phone hello only if it did not arrive promptly.
    core.helloRequestTimer = setTimeout(() => {
      if (!protocol.snapshot().ready) rawSend({ __tgw: 2, kind: 'helloRequest', client: 'vela' }).catch(() => {})
    }, 1200)
  }
  core.conn.onclose = data => {
    if (core.helloRequestTimer) clearTimeout(core.helloRequestTimer)
    core.helloRequestTimer = null
    protocol.onPhysicalState(false, data)
    core.readyState = 2
    notify(core.physicalHandlers, [false, data])
    notify(core.stateHandlers, [2, data])
  }
  core.conn.onerror = data => {
    protocol.onPhysicalState(false, data)
    core.readyState = 2
    notify(core.physicalHandlers, [false, data])
    notify(core.stateHandlers, [2, data])
  }
  core.conn.onmessage = event => {
    const raw = event && event.data
    if (protocol.receive(raw)) return
    // Unknown/unframed messages are deliberately not forwarded to business code.
    console.warn('[interconn] ignored message outside TGW/2 framing')
  }
  return core.conn
}

function attachApp(facade) {
  if (!facade || typeof facade.bridgeInit !== 'function') return false
  core.facade = facade
  core.facade.bridgeInit()
  return true
}

const api = {
  init,
  attachApp,
  send(data) {
    if (core.facade) return core.facade.bridgeSend(data)
    return protocol.send(data)
  },
  onMessage(handler) {
    if (core.facade) return core.facade.bridgeOnMessage(handler)
    if (typeof handler !== 'function') return () => {}
    if (core.messageHandlers.indexOf(handler) < 0) core.messageHandlers.push(handler)
    return () => { core.messageHandlers = core.messageHandlers.filter(fn => fn !== handler) }
  },
  onState(handler) {
    if (core.facade) return core.facade.bridgeOnState(handler)
    if (typeof handler !== 'function') return () => {}
    if (core.stateHandlers.indexOf(handler) < 0) core.stateHandlers.push(handler)
    return () => { core.stateHandlers = core.stateHandlers.filter(fn => fn !== handler) }
  },
  onPhysicalState(handler) {
    if (core.facade) return core.facade.bridgeOnPhysicalState(handler)
    if (typeof handler !== 'function') return () => {}
    if (core.physicalHandlers.indexOf(handler) < 0) core.physicalHandlers.push(handler)
    return () => { core.physicalHandlers = core.physicalHandlers.filter(fn => fn !== handler) }
  },
  getReadyState() {
    if (core.facade) return core.facade.bridgeGetReadyState()
    return protocol.snapshot().ready ? 1 : 2
  },
  queryReadyState() {
    if (core.facade) return core.facade.bridgeQueryReadyState()
    const active = init()
    return new Promise((resolve, reject) => {
      active.getReadyState({
        success: state => {
          core.lastSystemState = state
          if (!state || state.status !== 1) protocol.onPhysicalState(false, { source: 'getReadyState', state })
          resolve(state)
        },
        fail: (data, code) => reject({ code: code || (data && data.code), msg: (data && (data.data || data.msg)) || 'getReadyState failed' })
      })
    })
  },
  diagnose(timeout = 10000) {
    if (core.facade) return core.facade.bridgeDiagnose(timeout)
    const active = init()
    return new Promise((resolve, reject) => active.diagnosis({ timeout, success: resolve, fail: reject }))
  },
  protocolSnapshot() {
    if (core.facade) return core.facade.bridgeProtocolSnapshot()
    return protocol.snapshot()
  },
  resetForTests() {
    protocol.reset()
    core.readyState = 2
  }
}

export default api
