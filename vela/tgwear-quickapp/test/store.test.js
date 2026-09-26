import test from 'node:test'
import assert from 'node:assert/strict'
import store from '../src/utils/store.js'

test('page store delegates to the single app-owned facade', async () => {
  const calls = []
  const facade = {
    bridgeStoreGet(key) { calls.push(['get', key]); return key === 'dialogs' ? [{ peer: 'user:7' }] : 'root-value' },
    bridgeStoreSet(key, value) { calls.push(['set', key, value]); return 'stored' },
    bridgeStoreGetDialog(peer) { calls.push(['getDialog', peer]); return [{ id: 4 }] },
    bridgeStoreAppendDialog(peer, messages, prepend) { calls.push(['appendDialog', peer, messages.length, prepend]); return [{ id: 5 }] },
    bridgeStoreUpsertMessage(peer, message) { calls.push(['upsertMessage', peer, message.id]); return true },
    bridgeStoreReplaceMessageId(peer, localId, message, operationId) { calls.push(['replaceMessageId', peer, localId, operationId]); return true },
    bridgeStoreRemoveMessages(peer, ids) { calls.push(['removeMessages', peer, ids.length]); return true },
    bridgeStoreUpsertDialog(dialog) { calls.push(['upsertDialog', dialog.peer]); return true },
    bridgeStoreClearDialogs() { calls.push(['clearDialogs']); return true },
    bridgeStoreClearSession() { calls.push(['clearSession']); return true },
    bridgeStoreLoadSettings() { calls.push(['loadSettings']); return { fontScale: 1 } },
    bridgeStoreSaveSettings() { calls.push(['saveSettings']); return true },
    bridgeStoreSnapshot() { calls.push(['snapshot']); return { peers: 1 } }
  }

  assert.equal(store.attachApp(facade), true)
  assert.equal(store.get('session'), 'root-value')
  assert.equal(store.set('session', 'new-session'), 'stored')
  assert.equal(store.getDialog('user:7')[0].id, 4)
  assert.equal(store.appendDialog('user:7', [{ id: 5 }], true)[0].id, 5)
  assert.equal(store.upsertMessage('user:7', { id: 6 }), true)
  assert.equal(store.replaceMessageId('user:7', -6, { id: 6 }, 'op-6'), true)
  assert.equal(store.removeMessages('user:7', [6]), true)
  assert.equal(store.upsertDialog({ peer: 'user:7' }), true)
  assert.equal(store.clearDialogs(), true)
  assert.equal(store.clearSession(), true)
  assert.deepEqual(await store.loadSettings(), { fontScale: 1 })
  assert.equal(store.saveSettings(), true)
  assert.deepEqual(store.snapshot(), { peers: 1 })
  assert.ok(calls.length >= 12)
})
