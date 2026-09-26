import test from 'node:test'
import assert from 'node:assert/strict'
import { createProtocol, makeTransfer, utf8Encode, utf8Decode, crc32 } from '../src/utils/protocol.js'

const tick = ms => new Promise(resolve => setTimeout(resolve, ms))

function makeWearableProtocol(chunkBytes = 2048) {
  const watch = createProtocol({})
  let phoneSession = 'phone-test-session'
  const sentFrames = []
  const delivered = []
  let readyResolve
  const ready = new Promise(resolve => { readyResolve = resolve })

  watch.bind(frame => {
    sentFrames.push(frame)
    if (frame.kind === 'helloAck') setTimeout(() => readyResolve(true), 0)
    if (frame.kind === 'frame') {
      // A mock Android peer acknowledges each frame; commitAck is intentionally
      // delivered just after the final chunk ACK to exercise the race window.
      setTimeout(() => watch.receive({
        __tgw: 2, kind: 'ack', session: phoneSession,
        transferId: frame.transferId, seq: frame.seq, nextSeq: frame.seq + 1
      }), 0)
      if (frame.seq === frame.total - 1) {
        setTimeout(() => watch.receive({
          __tgw: 2, kind: 'commitAck', session: phoneSession,
          transferId: frame.transferId, seq: frame.seq, totalCrc32: frame.totalCrc32
        }), 1)
      }
    }
    return Promise.resolve()
  }, payload => delivered.push(payload))
  watch.onPhysicalState(true, { test: true })
  watch.receive({ __tgw: 2, kind: 'hello', session: phoneSession, maxChunk: chunkBytes })
  return { watch, ready, sentFrames, delivered, session: () => phoneSession }
}

test('UTF-8 codec round-trips Chinese, emoji, and mixed scripts', () => {
  const source = '私聊同步：你好，TG Wear 👋🏽 — Nagram'
  assert.equal(utf8Decode(utf8Encode(source)), source)
})

test('frames respect byte and chunk limits, and oversize data is rejected', () => {
  const text = '表情🙂'.repeat(700)
  const frames = makeTransfer(text, 'session', 'transfer')
  assert.ok(frames.length > 1 && frames.length <= 64)
  assert.ok(frames.every(frame => frame.byteLength <= 2048))
  assert.equal(frames.reduce((sum, frame) => sum + frame.byteLength, 0), utf8Encode(text).length)
  assert.equal(crc32(utf8Encode(text)), frames[0].totalCrc32)
  assert.throws(() => makeTransfer('x'.repeat(128 * 1024 + 1), 's', 'large'), /128 KiB/)
})

test('handshake negotiates and enforces a smaller frame size', async () => {
  const pair = makeWearableProtocol(512)
  await pair.ready
  await tick(0)
  const result = await pair.watch.send(JSON.stringify({ text: '甲'.repeat(2400) }))
  assert.equal(pair.watch.snapshot().chunkBytes, 512)
  assert.ok(result.chunks > 1)
  assert.ok(pair.sentFrames.filter(frame => frame.kind === 'frame').every(frame => frame.byteLength <= 512))
})

test('watch sender completes chunk ACK and commitAck round trip', async () => {
  const pair = makeWearableProtocol()
  await pair.ready
  await tick(0)
  const payload = JSON.stringify({ method: 'messages.sendText', params: { peer: 'user:42', text: '你好🙂'.repeat(1800) } })
  const result = await pair.watch.send(payload)
  assert.ok(result.chunks > 1)
  assert.equal(result.bytes, utf8Encode(payload).length)
  assert.equal(pair.watch.snapshot().ready, true)
  assert.equal(pair.watch.snapshot().queued, 0)
})

test('watch receiver delivers a reconstructed message once and re-ACKs a duplicate final frame', async () => {
  const pair = makeWearableProtocol()
  await pair.ready
  await tick(0)
  const text = JSON.stringify({ __rpc: true, id: 'req-1', method: 'messages.getHistory', params: { peer: 'user:9', text: '腕上消息🙂'.repeat(900) } })
  const frames = makeTransfer(text, pair.session(), 'phone-transfer-1')
  frames.forEach(frame => pair.watch.receive(frame))
  await tick(10)
  assert.equal(pair.delivered.length, 1)
  assert.equal(pair.delivered[0], text)
  const controlsBefore = pair.sentFrames.length
  pair.watch.receive(frames[frames.length - 1])
  await tick(0)
  assert.equal(pair.delivered.length, 1)
  assert.ok(pair.sentFrames.length >= controlsBefore + 2)
  assert.ok(pair.sentFrames.slice(controlsBefore).some(frame => frame.kind === 'commitAck'))
})

test('watch receiver rejects a corrupted chunk checksum', async () => {
  const pair = makeWearableProtocol()
  await pair.ready
  await tick(0)
  const frames = makeTransfer('{"hello":"world"}', pair.session(), 'phone-transfer-bad')
  frames[0].chunkCrc32 = '00000000'
  pair.watch.receive(frames[0])
  await tick(0)
  assert.ok(pair.sentFrames.some(frame => frame.kind === 'nack' && frame.transferId === 'phone-transfer-bad'))
  assert.equal(pair.delivered.length, 0)
})

test('watch receiver safely rejects malformed Base64 without throwing', async () => {
  const pair = makeWearableProtocol()
  await pair.ready
  await tick(0)
  const frames = makeTransfer('{"hello":"world"}', pair.session(), 'phone-transfer-base64')
  frames[0].data = '%%%!'
  assert.doesNotThrow(() => pair.watch.receive(frames[0]))
  await tick(0)
  assert.ok(pair.sentFrames.some(frame => frame.kind === 'nack' && frame.transferId === 'phone-transfer-base64'))
  assert.equal(pair.delivered.length, 0)
})
