/*
 * TG Wear application-level framing. This is deliberately not SimpleFetch (SF_*)
 * and runs over the existing Xiaomi system.interconnect object channel.
 */
const VERSION = 2
const DEFAULT_CHUNK_BYTES = 2048
const MAX_LOGICAL_BYTES = 128 * 1024
const MAX_CHUNKS = 64
const MAX_ASSEMBLERS = 1
const ACK_TIMEOUT_MS = 3000
const MAX_RETRIES = 3
const ASSEMBLY_TTL_MS = 30000
const COMPLETED_TTL_MS = 30000
const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

function utf8Encode(text) {
  const out = []
  for (let i = 0; i < text.length; i++) {
    let cp = text.charCodeAt(i)
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
      const low = text.charCodeAt(i + 1)
      if (low >= 0xdc00 && low <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00)
        i++
      }
    }
    if (cp < 0x80) out.push(cp)
    else if (cp < 0x800) out.push(0xc0 | (cp >> 6), 0x80 | (cp & 63))
    else if (cp < 0x10000) out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63))
    else out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63))
  }
  return out
}

function utf8Decode(bytes) {
  let out = ''
  for (let i = 0; i < bytes.length;) {
    const a = bytes[i++] & 255
    let cp = a
    if (a >= 0xc2 && a <= 0xdf && i < bytes.length) cp = ((a & 31) << 6) | (bytes[i++] & 63)
    else if (a >= 0xe0 && a <= 0xef && i + 1 < bytes.length) cp = ((a & 15) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)
    else if (a >= 0xf0 && a <= 0xf4 && i + 2 < bytes.length) cp = ((a & 7) << 18) | ((bytes[i++] & 63) << 12) | ((bytes[i++] & 63) << 6) | (bytes[i++] & 63)
    else if (a >= 0x80) cp = 0xfffd
    if (cp <= 0xffff) out += String.fromCharCode(cp)
    else {
      cp -= 0x10000
      out += String.fromCharCode(0xd800 | (cp >> 10), 0xdc00 | (cp & 1023))
    }
  }
  return out
}

function base64Encode(bytes) {
  let out = ''
  for (let i = 0; i < bytes.length; i += 3) {
    const a = bytes[i] & 255
    const hasB = i + 1 < bytes.length
    const hasC = i + 2 < bytes.length
    const b = hasB ? bytes[i + 1] & 255 : 0
    const c = hasC ? bytes[i + 2] & 255 : 0
    out += BASE64[a >> 2]
    out += BASE64[((a & 3) << 4) | (b >> 4)]
    out += hasB ? BASE64[((b & 15) << 2) | (c >> 6)] : '='
    out += hasC ? BASE64[c & 63] : '='
  }
  return out
}

function base64Decode(text) {
  if (typeof text !== 'string' || text.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(text)) throw new Error('bad base64')
  const out = []
  for (let i = 0; i < text.length; i += 4) {
    const a = BASE64.indexOf(text[i])
    const b = BASE64.indexOf(text[i + 1])
    const c = text[i + 2] === '=' ? 0 : BASE64.indexOf(text[i + 2])
    const d = text[i + 3] === '=' ? 0 : BASE64.indexOf(text[i + 3])
    if (a < 0 || b < 0 || c < 0 || d < 0) throw new Error('bad base64')
    out.push((a << 2) | (b >> 4))
    if (text[i + 2] !== '=') out.push(((b & 15) << 4) | (c >> 2))
    if (text[i + 3] !== '=') out.push(((c & 3) << 6) | d)
  }
  return out
}

function crc32(bytes) {
  let crc = -1
  for (let i = 0; i < bytes.length; i++) {
    crc ^= bytes[i] & 255
    for (let j = 0; j < 8; j++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0)
  }
  return ((crc ^ -1) >>> 0).toString(16).padStart(8, '0')
}

function makeTransfer(text, session, transferId, chunkBytes = DEFAULT_CHUNK_BYTES) {
  const bytes = utf8Encode(text)
  if (bytes.length > MAX_LOGICAL_BYTES) throw new Error('logical payload exceeds 128 KiB')
  const size = Math.max(128, Math.min(DEFAULT_CHUNK_BYTES, chunkBytes | 0))
  const total = Math.max(1, Math.ceil(bytes.length / size))
  if (total > MAX_CHUNKS) throw new Error('logical payload exceeds 64 chunks')
  const totalCrc32 = crc32(bytes)
  const frames = []
  for (let seq = 0; seq < total; seq++) {
    const chunk = bytes.slice(seq * size, Math.min(bytes.length, (seq + 1) * size))
    frames.push({
      __tgw: VERSION, kind: 'frame', session, transferId, seq, total,
      byteLength: chunk.length, totalBytes: bytes.length, totalCrc32,
      chunkCrc32: crc32(chunk), data: base64Encode(chunk)
    })
  }
  return frames
}

function isFrame(value) {
  return !!value && typeof value === 'object' && value.__tgw === VERSION && typeof value.kind === 'string'
}

function createProtocol(targetRoot) {
  const shared = targetRoot || (typeof globalThis !== 'undefined' ? globalThis : global)
  const key = '__tgWearProtocolV2'
  if (shared[key]) return shared[key]

  const state = {
    rawSend: null,
    onPayload: null,
    onState: null,
    connected: false,
    ready: false,
    session: '',
    localSession: '',
    chunkBytes: DEFAULT_CHUNK_BYTES,
    sequence: 0,
    queueDepth: 0,
    outgoing: Promise.resolve(),
    ackWaiters: {},
    commitWaiters: {},
    commitSeen: {},
    assemblies: {},
    completed: {},
    lastError: '',
    retryCount: 0
  }

  function emitState(name, info) {
    if (state.onState) {
      try { state.onState(name, info || {}) } catch (e) { console.warn('[TGW2] state listener failed') }
    }
  }

  function rawSend(frame) {
    if (!state.rawSend) return Promise.reject(new Error('interconnect sender unavailable'))
    return Promise.resolve().then(() => state.rawSend(frame))
  }

  function failWaiters(error) {
    Object.keys(state.ackWaiters).forEach(k => { state.ackWaiters[k].reject(error); delete state.ackWaiters[k] })
    Object.keys(state.commitWaiters).forEach(k => { state.commitWaiters[k].reject(error); delete state.commitWaiters[k] })
  }

  function rememberBounded(cache, keyName, value, cap) {
    const now = Date.now()
    Object.keys(cache).forEach(key => { if (cache[key].expires <= now) delete cache[key] })
    while (Object.keys(cache).length >= cap && !Object.prototype.hasOwnProperty.call(cache, keyName)) {
      delete cache[Object.keys(cache)[0]]
    }
    cache[keyName] = value
  }

  function physicalState(connected, info) {
    const next = !!connected
    if (state.connected === next && (!next || state.ready)) return
    state.connected = next
    state.ready = false
    state.session = ''
    state.assemblies = {}
    state.completed = {}
    state.commitSeen = {}
    failWaiters(new Error(next ? 'session renegotiating' : 'interconnect disconnected'))
    emitState(next ? 'transport-up' : 'transport-down', info)
    if (next) {
      state.localSession = ''
    }
  }

  function settleAck(frame, positive) {
    const keyName = frame.transferId + ':' + frame.seq
    const waiter = state.ackWaiters[keyName]
    if (!waiter || frame.session !== state.session) return
    delete state.ackWaiters[keyName]
    positive ? waiter.resolve(true) : waiter.reject(new Error(frame.reason || 'peer rejected chunk'))
  }

  function receiveFrame(frame) {
    if (frame.session !== state.session) return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: 0, reason: 'session mismatch' }).catch(() => {})
    if (typeof frame.transferId !== 'string' || frame.transferId.length > 80 || !Number.isInteger(frame.seq) || !Number.isInteger(frame.total) || frame.total < 1 || frame.total > MAX_CHUNKS || frame.seq < 0 || frame.seq >= frame.total || !Number.isInteger(frame.totalBytes) || frame.totalBytes < 0 || frame.totalBytes > MAX_LOGICAL_BYTES || !Number.isInteger(frame.byteLength) || frame.byteLength < 0 || frame.byteLength > state.chunkBytes || typeof frame.totalCrc32 !== 'string' || !/^[0-9a-fA-F]{8}$/.test(frame.totalCrc32) || typeof frame.data !== 'string' || frame.data.length > Math.ceil(state.chunkBytes / 3) * 4 + 4) return

    const finished = state.completed[frame.transferId]
    if (finished && finished.expires > Date.now()) {
      rawSend({ __tgw: VERSION, kind: 'ack', session: state.session, transferId: frame.transferId, seq: frame.seq, nextSeq: frame.seq + 1 }).catch(() => {})
      rawSend({ __tgw: VERSION, kind: 'commitAck', session: state.session, transferId: frame.transferId, seq: frame.seq, totalCrc32: finished.totalCrc32 }).catch(() => {})
      return
    }
    let item = state.assemblies[frame.transferId]
    if (!item) {
      const keys = Object.keys(state.assemblies)
      if (keys.length >= MAX_ASSEMBLERS) return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: 0, reason: 'receiver busy' }).catch(() => {})
      item = state.assemblies[frame.transferId] = { total: frame.total, totalBytes: frame.totalBytes, totalCrc32: frame.totalCrc32, chunks: {}, received: 0, receivedBytes: 0, nextSeq: 0, expires: Date.now() + ASSEMBLY_TTL_MS }
    }
    if (item.total !== frame.total || item.totalBytes !== frame.totalBytes || item.totalCrc32 !== frame.totalCrc32 || item.expires < Date.now()) {
      delete state.assemblies[frame.transferId]
      return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: 0, reason: 'transfer metadata mismatch' }).catch(() => {})
    }
    let chunk
    try { chunk = base64Decode(frame.data) }
    catch (e) { return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: item.nextSeq, reason: 'invalid base64' }).catch(() => {}) }
    if (chunk.length !== frame.byteLength || chunk.length > state.chunkBytes || crc32(chunk) !== frame.chunkCrc32) return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: item.nextSeq, reason: 'chunk checksum/length invalid' }).catch(() => {})
    if (!Object.prototype.hasOwnProperty.call(item.chunks, frame.seq)) {
      if (frame.seq !== item.nextSeq) return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: item.nextSeq, reason: 'out of order' }).catch(() => {})
      item.chunks[frame.seq] = chunk
      item.received++
      item.receivedBytes += chunk.length
      item.nextSeq++
    }
    rawSend({ __tgw: VERSION, kind: 'ack', session: state.session, transferId: frame.transferId, seq: frame.seq, nextSeq: item.nextSeq }).catch(() => {})
    if (item.received !== item.total) return
    if (item.receivedBytes !== item.totalBytes) {
      delete state.assemblies[frame.transferId]
      return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: 0, reason: 'total length mismatch' }).catch(() => {})
    }
    const bytes = new Array(item.totalBytes)
    let byteOffset = 0
    for (let i = 0; i < item.total; i++) {
      for (let j = 0; j < item.chunks[i].length; j++) bytes[byteOffset++] = item.chunks[i][j]
    }
    if (crc32(bytes) !== item.totalCrc32) {
      delete state.assemblies[frame.transferId]
      return rawSend({ __tgw: VERSION, kind: 'nack', session: state.session, transferId: frame.transferId, seq: frame.seq, expectedSeq: 0, reason: 'total checksum mismatch' }).catch(() => {})
    }
    const text = utf8Decode(bytes)
    delete state.assemblies[frame.transferId]
    rememberBounded(state.completed, frame.transferId, { totalCrc32: item.totalCrc32, expires: Date.now() + COMPLETED_TTL_MS }, 128)
    rawSend({ __tgw: VERSION, kind: 'commitAck', session: state.session, transferId: frame.transferId, seq: frame.seq, totalCrc32: item.totalCrc32 }).catch(() => {})
    if (state.onPayload) {
      setTimeout(() => {
        try { state.onPayload(text) } catch (e) { console.warn('[TGW2] payload listener failed') }
      }, 0)
    }
  }

  function receive(raw) {
    if (typeof raw === 'string' && raw.length > 8192) return true
    let frame = raw
    if (typeof raw === 'string') {
      try { frame = JSON.parse(raw) } catch (e) { return false }
    }
    if (!isFrame(frame)) return false
    if (frame.kind === 'hello') {
      if (typeof frame.session !== 'string' || frame.session.length > 80) return true
      state.session = frame.session
      state.chunkBytes = Math.max(128, Math.min(DEFAULT_CHUNK_BYTES, frame.maxChunk | 0))
      state.ready = false
      state.assemblies = {}
      state.completed = {}
      state.commitSeen = {}
      failWaiters(new Error('peer changed session'))
      const chunkBytes = state.chunkBytes
      rawSend({ __tgw: VERSION, kind: 'helloAck', session: state.session, maxChunk: chunkBytes })
        .then(() => { state.ready = true; emitState('ready', { session: state.session, maxChunk: chunkBytes }) })
        .catch(e => { state.lastError = String(e && e.message || e); emitState('error', { error: state.lastError }) })
      return true
    }
    if (frame.kind === 'helloRequest') return true
    if (!state.ready || frame.session !== state.session) return true
    if (frame.kind === 'frame') { receiveFrame(frame); return true }
    if (frame.kind === 'ack') { settleAck(frame, true); return true }
    if (frame.kind === 'nack') {
      const k = frame.transferId + ':' + frame.seq
      const w = state.ackWaiters[k]
      if (w && frame.session === state.session) { delete state.ackWaiters[k]; w.reject(new Error(frame.reason || 'peer rejected chunk')) }
      return true
    }
    if (frame.kind === 'commitAck') {
      const w = state.commitWaiters[frame.transferId]
      if (frame.session === state.session) {
        const crc = String(frame.totalCrc32 || '').toLowerCase()
        if (w) {
          delete state.commitWaiters[frame.transferId]
          crc === w.expectedCrc ? w.resolve(true) : w.reject(new Error('commit ACK checksum mismatch'))
        } else rememberBounded(state.commitSeen, frame.transferId, { crc, expires: Date.now() + COMPLETED_TTL_MS }, 128)
      }
      return true
    }
    return true
  }

  function waitForAck(transferId, seq) {
    const k = transferId + ':' + seq
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (state.ackWaiters[k]) { delete state.ackWaiters[k]; reject(new Error('chunk ACK timeout')) }
      }, ACK_TIMEOUT_MS)
      state.ackWaiters[k] = {
        resolve: value => { clearTimeout(timer); resolve(value) },
        reject: error => { clearTimeout(timer); reject(error) }
      }
    })
  }

  function waitForCommit(transferId, expectedCrc) {
    const seen = state.commitSeen[transferId]
    if (seen && seen.expires > Date.now()) {
      delete state.commitSeen[transferId]
      return seen.crc === expectedCrc ? Promise.resolve(true) : Promise.reject(new Error('commit ACK checksum mismatch'))
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (state.commitWaiters[transferId]) { delete state.commitWaiters[transferId]; reject(new Error('commit ACK timeout')) }
      }, ACK_TIMEOUT_MS)
      state.commitWaiters[transferId] = {
        expectedCrc,
        resolve: value => { clearTimeout(timer); resolve(value) },
        reject: error => { clearTimeout(timer); reject(error) }
      }
    })
  }

  async function sendChunk(frame) {
    let lastError
    for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      if (!state.ready || frame.session !== state.session) throw new Error('session lost during transfer')
      const ack = waitForAck(frame.transferId, frame.seq)
      try { await rawSend(frame) }
      catch (e) {
        const keyName = frame.transferId + ':' + frame.seq
        const waiter = state.ackWaiters[keyName]
        if (waiter) { delete state.ackWaiters[keyName]; waiter.reject(e) }
      }
      try { await ack; return }
      catch (e) {
        lastError = e
        state.retryCount++
        if (attempt >= MAX_RETRIES) break
        await new Promise(resolve => setTimeout(resolve, 1000 * (1 << attempt)))
      }
    }
    state.lastError = String(lastError && lastError.message || 'chunk retries exhausted')
    throw new Error(state.lastError)
  }

  async function sendLogical(text) {
    if (state.queueDepth >= 8) return Promise.reject(new Error('TG Wear send queue full'))
    state.queueDepth++
    const task = state.outgoing.then(async () => {
      if (typeof text !== 'string') text = JSON.stringify(text)
      const bytes = utf8Encode(text)
      if (bytes.length > MAX_LOGICAL_BYTES) throw new Error('logical payload exceeds 128 KiB')
      if (!state.ready || !state.session) throw new Error('TG Wear session not ready')
      const transferId = 'v-' + Date.now().toString(36) + '-' + (++state.sequence).toString(36)
      const frames = makeTransfer(text, state.session, transferId, state.chunkBytes)
      for (let i = 0; i < frames.length - 1; i++) await sendChunk(frames[i])
      const finalFrame = frames[frames.length - 1]
      await sendChunk(finalFrame)
      let committed = false
      for (let attempt = 0; attempt <= MAX_RETRIES && !committed; attempt++) {
        try { await waitForCommit(transferId, finalFrame.totalCrc32); committed = true }
        catch (e) {
          state.retryCount++
          if (attempt >= MAX_RETRIES) throw e
          await sendChunk(finalFrame)
          await new Promise(resolve => setTimeout(resolve, 1000 * (1 << attempt)))
        }
      }
      return { transferId, bytes: bytes.length, chunks: frames.length }
    })
    state.outgoing = task.catch(() => {}).finally(() => { state.queueDepth = Math.max(0, state.queueDepth - 1) })
    return task
  }

  const api = {
    bind(sender, onPayload, onState) {
      state.rawSend = sender
      state.onPayload = onPayload
      state.onState = onState
      return api
    },
    onPhysicalState: physicalState,
    receive,
    send: sendLogical,
    snapshot() {
      return { transport: state.connected, ready: state.ready, session: state.session, chunkBytes: state.chunkBytes, queued: state.queueDepth, assembling: Object.keys(state.assemblies).length, retries: state.retryCount, lastError: state.lastError }
    },
    reset() {
      state.connected = false
      state.ready = false
      state.session = ''
      state.localSession = ''
      state.assemblies = {}
      state.completed = {}
      state.commitSeen = {}
      failWaiters(new Error('protocol reset'))
    }
  }
  shared[key] = api
  return api
}

const protocol = createProtocol()
export { utf8Encode, utf8Decode, base64Encode, base64Decode, crc32, makeTransfer, isFrame, createProtocol }
export default protocol
