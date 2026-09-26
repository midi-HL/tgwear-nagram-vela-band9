package org.telegram.tgwear;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

/**
 * TG Wear v2 application framing over the existing Xiaomi MessageApi transport.
 * This class intentionally has no SimpleFetch/SF_* protocol dependency.
 */
public final class ReliableWearConnection implements WearConnection {
    private static final String TAG = "TGWear/Protocol";
    private static final int VERSION = 2;
    private static final int CHUNK_BYTES = 2048;
    private static final int MAX_LOGICAL_BYTES = 128 * 1024;
    private static final int MAX_CHUNKS = 64;
    private static final int MAX_WIRE_FRAME_CHARS = 8192;
    private static final long ACK_TIMEOUT_MS = 3000L;
    private static final int MAX_RETRIES = 3;
    private static final long ASSEMBLY_TTL_MS = 30_000L;

    private final WearConnection transport;
    private final ExecutorService sendQueue = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), r -> daemonThread(r, "TGWear-send-queue"), new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService wireQueue = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), r -> daemonThread(r, "TGWear-wire-queue"), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong transferSequence = new AtomicLong();
    private final Object lock = new Object();
    private final Map<String, AckWaiter> ackWaiters = new HashMap<>();
    private final Map<String, Assembly> assemblies = new HashMap<>();
    private final Map<String, CompletedTransfer> completedTransfers = new HashMap<>();
    private volatile Listener listener;
    private volatile boolean physicalReady;
    private volatile boolean protocolReady;
    private volatile boolean destroyed;
    private volatile String session = "";
    private volatile int negotiatedChunkBytes = CHUNK_BYTES;
    @Nullable private volatile JSONObject lastHelloFrame;
    private volatile String lastError = "";

    public ReliableWearConnection(@NonNull WearConnection transport) {
        this.transport = transport;
        transport.setMessageListener(new Listener() {
            @Override public void onReadyStateChanged(boolean ready, @Nullable Object info) {
                onPhysicalState(ready, info);
            }
            @Override public void onMessage(@NonNull String message) {
                onTransportMessage(message);
            }
        });
    }

    @Override public void init() {
        destroyed = false;
        transport.init();
    }

    @Override public void reconnect() {
        if (destroyed) destroyed = false;
        transport.reconnect();
    }

    @Override public boolean send(@NonNull String data) {
        if (!protocolReady || destroyed) {
            Log.w(TAG, "logical send rejected: TGW/2 session not ready");
            return false;
        }
        final String captured = data;
        try {
            sendQueue.execute(() -> sendLogical(captured));
            return true; // accepted into bounded logical queue; remote ACK remains authoritative
        } catch (RuntimeException error) {
            lastError = safeMessage(error);
            Log.e(TAG, "send queue rejected payload", error);
            return false;
        }
    }

    @Override public void setMessageListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override public boolean isReady() {
        return physicalReady && protocolReady && !destroyed;
    }

    public String getLastError() { return lastError; }

    private void onPhysicalState(boolean ready, @Nullable Object info) {
        physicalReady = ready;
        protocolReady = false;
        WearDiagnosticStatus.protocolState(false, ready ? "Xiaomi transport up; negotiating TGW/2" : "Xiaomi transport down");
        session = "";
        negotiatedChunkBytes = CHUNK_BYTES;
        failWaiters(new IllegalStateException(ready ? "TGW session renegotiating" : "wearable transport disconnected"));
        synchronized (lock) { assemblies.clear(); completedTransfers.clear(); }
        Listener target = listener;
        if (!ready && target != null) target.onReadyStateChanged(false, info);
        if (ready && !destroyed) {
            sendQueue.execute(this::negotiateSession);
        }
    }

    private void negotiateSession() {
        if (destroyed || !physicalReady) return;
        final String candidate = "p-" + UUID.randomUUID().toString();
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (lock) {
            session = candidate;
            helloWaiter = latch;
        }
        JSONObject hello = new JSONObject();
        try {
            hello.put("__tgw", VERSION);
            hello.put("kind", "hello");
            hello.put("session", candidate);
            hello.put("maxChunk", CHUNK_BYTES);
        } catch (JSONException impossible) { return; }
        lastHelloFrame = hello;

        for (int attempt = 0; attempt <= MAX_RETRIES && !protocolReady && physicalReady && !destroyed; attempt++) {
            sendWire(hello);
            try {
                if (latch.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt < MAX_RETRIES) sleep(1000L << attempt);
        }
        synchronized (lock) { if (helloWaiter == latch) helloWaiter = null; }
        if (!protocolReady && physicalReady && !destroyed) {
            lastError = "TGW/2 handshake timeout";
            Log.w(TAG, lastError);
            WearDiagnosticStatus.protocolState(false, lastError);
            Listener target = listener;
            if (target != null) target.onReadyStateChanged(false, lastError);
        }
    }

    @Nullable private CountDownLatch helloWaiter;

    private void onTransportMessage(@NonNull String raw) {
        if (destroyed || raw.length() > MAX_WIRE_FRAME_CHARS) return;
        final JSONObject frame;
        try { frame = new JSONObject(raw); }
        catch (JSONException notProtocolFrame) {
            Log.w(TAG, "dropping non-JSON message at transport boundary");
            return;
        }
        if (frame.optInt("__tgw", -1) != VERSION) {
            Log.w(TAG, "dropping unframed/unknown-version message");
            return;
        }
        WearDiagnosticStatus.protocolFrame(true);
        String kind = frame.optString("kind", "");
        if ("helloAck".equals(kind)) {
            CountDownLatch latch;
            synchronized (lock) {
                if (!session.equals(frame.optString("session", ""))) return;
                negotiatedChunkBytes = Math.max(128, Math.min(CHUNK_BYTES, frame.optInt("maxChunk", CHUNK_BYTES)));
                protocolReady = true;
                latch = helloWaiter;
            }
            WearDiagnosticStatus.protocolChunkBytes(negotiatedChunkBytes);
            WearDiagnosticStatus.protocolState(true, "TGW/2 session ready");
            if (latch != null) latch.countDown();
            Log.i(TAG, "TGW/2 session ready chunkBytes=" + Math.min(CHUNK_BYTES, frame.optInt("maxChunk", CHUNK_BYTES)));
            Listener target = listener;
            if (target != null) target.onReadyStateChanged(true, "tgw2-session-ready");
            return;
        }
        if ("helloRequest".equals(kind)) {
            JSONObject hello = lastHelloFrame;
            if (hello != null && physicalReady) sendWire(hello);
            return;
        }
        if (!protocolReady || !session.equals(frame.optString("session", ""))) return;
        if ("ack".equals(kind) || "nack".equals(kind)) {
            completeWaiter(frame, "ack".equals(kind));
        } else if ("commitAck".equals(kind)) {
            completeWaiter(frame, true);
        } else if ("frame".equals(kind)) {
            receiveDataFrame(frame);
        } else {
            Log.w(TAG, "unknown TGW/2 frame kind=" + kind);
        }
    }

    private void completeWaiter(JSONObject frame, boolean ok) {
        String key = frame.optString("transferId", "") + ":" + frame.optInt("seq", -2);
        if ("commitAck".equals(frame.optString("kind"))) key += ":commit";
        AckWaiter waiter;
        synchronized (lock) { waiter = ackWaiters.remove(key); }
        if (waiter == null) return;
        waiter.ok = ok;
        waiter.reason = frame.optString("reason", "peer rejected frame");
        waiter.latch.countDown();
    }

    private void receiveDataFrame(JSONObject frame) {
        String transferId = frame.optString("transferId", "");
        int seq = frame.optInt("seq", -1);
        int total = frame.optInt("total", -1);
        int totalBytes = frame.optInt("totalBytes", -1);
        int byteLength = frame.optInt("byteLength", -1);
        String totalCrc = frame.optString("totalCrc32", "");
        String data = frame.optString("data", "");
        if (transferId.isEmpty() || transferId.length() > 80 || total < 1 || total > MAX_CHUNKS || seq < 0 || seq >= total || totalBytes < 0 || totalBytes > MAX_LOGICAL_BYTES || byteLength < 0 || byteLength > negotiatedChunkBytes || !totalCrc.matches("[0-9a-fA-F]{8}")) {
            sendControl("nack", transferId, seq, 0, "invalid frame metadata");
            return;
        }
        final byte[] chunk;
        try { chunk = Base64.decode(data, Base64.DEFAULT); }
        catch (IllegalArgumentException badBase64) { sendControl("nack", transferId, seq, 0, "invalid base64"); return; }
        if (chunk.length != byteLength || !crc32(chunk).equalsIgnoreCase(frame.optString("chunkCrc32", ""))) {
            sendControl("nack", transferId, seq, 0, "chunk checksum/length invalid");
            return;
        }
        CompletedTransfer completed;
        synchronized (lock) {
            pruneCompletedTransfers();
            completed = completedTransfers.get(transferId);
        }
        if (completed != null) {
            if (completed.totalCrc32.equalsIgnoreCase(totalCrc) && seq == total - 1) {
                sendControl("ack", transferId, seq, total, null);
                sendCommitAck(transferId, seq, total, totalCrc);
            } else {
                sendControl("nack", transferId, seq, 0, "transfer already committed");
            }
            return;
        }
        Assembly assembly;
        synchronized (lock) {
            pruneAssemblies();
            assembly = assemblies.get(transferId);
            if (assembly == null) {
                if (assemblies.size() >= 1) { sendControl("nack", transferId, seq, 0, "receiver busy"); return; }
                assembly = new Assembly(total, totalBytes, totalCrc, System.currentTimeMillis() + ASSEMBLY_TTL_MS);
                assemblies.put(transferId, assembly);
            }
            if (assembly.total != total || assembly.totalBytes != totalBytes || !assembly.totalCrc32.equals(totalCrc) || assembly.expiresAt < System.currentTimeMillis()) {
                assemblies.remove(transferId);
                sendControl("nack", transferId, seq, 0, "transfer metadata mismatch");
                return;
            }
            if (assembly.chunks[seq] == null) {
                if (seq != assembly.nextSeq) { sendControl("nack", transferId, seq, assembly.nextSeq, "out of order"); return; }
                assembly.chunks[seq] = chunk;
                assembly.receivedBytes += chunk.length;
                assembly.received++;
                assembly.nextSeq++;
            }
        }
        sendControl("ack", transferId, seq, assembly.nextSeq, null);
        if (assembly.received != assembly.total) return;
        if (assembly.receivedBytes != assembly.totalBytes) {
            synchronized (lock) { assemblies.remove(transferId); }
            sendControl("nack", transferId, seq, 0, "total length mismatch");
            return;
        }
        byte[] all = new byte[assembly.receivedBytes];
        int offset = 0;
        for (byte[] part : assembly.chunks) {
            if (part == null || offset + part.length > all.length) {
                synchronized (lock) { assemblies.remove(transferId); }
                sendControl("nack", transferId, seq, 0, "missing chunk");
                return;
            }
            System.arraycopy(part, 0, all, offset, part.length);
            offset += part.length;
        }
        if (!crc32(all).equalsIgnoreCase(assembly.totalCrc32)) {
            synchronized (lock) { assemblies.remove(transferId); }
            sendControl("nack", transferId, seq, 0, "total checksum mismatch");
            return;
        }
        final String payload = new String(all, StandardCharsets.UTF_8);
        synchronized (lock) {
            assemblies.remove(transferId);
            if (completedTransfers.size() >= 128) {
                String oldest = completedTransfers.keySet().iterator().next();
                completedTransfers.remove(oldest);
            }
            completedTransfers.put(transferId, new CompletedTransfer(assembly.totalCrc32, System.currentTimeMillis() + ASSEMBLY_TTL_MS));
        }
        sendCommitAck(transferId, seq, assembly.total, assembly.totalCrc32);
        WearDiagnosticStatus.protocolPayloadReceived();
        Listener target = listener;
        if (target != null) target.onMessage(payload);
    }

    private void sendLogical(String payload) {
        if (destroyed || !protocolReady || !physicalReady) {
            Log.w(TAG, "queued logical payload dropped because TGW/2 session is unavailable");
            return;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_LOGICAL_BYTES) {
            lastError = "logical payload exceeds 128 KiB";
            Log.w(TAG, lastError + " bytes=" + bytes.length);
            return;
        }
        final String transferId = "p-" + Long.toString(transferSequence.incrementAndGet(), 36) + "-" + UUID.randomUUID().toString().substring(0, 8);
        int chunkBytes = negotiatedChunkBytes;
        int total = Math.max(1, (bytes.length + chunkBytes - 1) / chunkBytes);
        if (total > MAX_CHUNKS) {
            lastError = "logical payload exceeds 64 chunks";
            return;
        }
        String totalCrc = crc32(bytes);
        for (int seq = 0; seq < total; seq++) {
            int start = seq * chunkBytes;
            int end = Math.min(bytes.length, start + chunkBytes);
            byte[] part = new byte[end - start];
            System.arraycopy(bytes, start, part, 0, part.length);
            JSONObject frame = new JSONObject();
            try {
                frame.put("__tgw", VERSION);
                frame.put("kind", "frame");
                frame.put("session", session);
                frame.put("transferId", transferId);
                frame.put("seq", seq);
                frame.put("total", total);
                frame.put("byteLength", part.length);
                frame.put("totalBytes", bytes.length);
                frame.put("totalCrc32", totalCrc);
                frame.put("chunkCrc32", crc32(part));
                frame.put("data", Base64.encodeToString(part, Base64.NO_WRAP));
            } catch (JSONException impossible) { return; }
            if (!sendAndAwait(frame, transferId, seq, seq == total - 1)) return;
        }
    }

    private boolean sendAndAwait(JSONObject frame, String transferId, int seq, boolean commit) {
        String key = transferId + ":" + seq + (commit ? ":commit" : "");
        for (int attempt = 0; attempt <= MAX_RETRIES && !destroyed && protocolReady; attempt++) {
            AckWaiter waiter = new AckWaiter();
            synchronized (lock) { ackWaiters.put(key, waiter); }
            if (!sendWire(frame)) {
                synchronized (lock) { ackWaiters.remove(key); }
                return false;
            }
            try {
                if (waiter.latch.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) && waiter.ok) return true;
                lastError = waiter.reason;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                synchronized (lock) { ackWaiters.remove(key); }
                return false;
            }
            synchronized (lock) { ackWaiters.remove(key); }
            if (attempt < MAX_RETRIES) sleep(1000L << attempt);
        }
        lastError = "TGW/2 ACK retries exhausted transfer=" + transferId + " seq=" + seq;
        Log.e(TAG, lastError);
        return false;
    }

    private void sendControl(String kind, String transferId, int seq, int nextSeq, @Nullable String reason) {
        JSONObject frame = control(kind, transferId, seq, nextSeq, reason);
        if (frame != null && physicalReady) sendWire(frame);
    }

    private void sendCommitAck(String transferId, int seq, int total, String totalCrc32) {
        JSONObject frame = control("commitAck", transferId, seq, total, null);
        if (frame == null) return;
        try { frame.put("totalCrc32", totalCrc32); sendWire(frame); }
        catch (JSONException ignored) { }
    }

    @Nullable private JSONObject control(String kind, String transferId, int seq, int nextSeq, @Nullable String reason) {
        JSONObject frame = new JSONObject();
        try {
            frame.put("__tgw", VERSION);
            frame.put("kind", kind);
            frame.put("session", session);
            frame.put("transferId", transferId);
            frame.put("seq", seq);
            frame.put("nextSeq", nextSeq);
            if (reason != null) frame.put("reason", reason);
            return frame;
        } catch (JSONException impossible) { return null; }
    }

    private void failWaiters(Throwable error) {
        synchronized (lock) {
            for (AckWaiter waiter : ackWaiters.values()) {
                waiter.ok = false;
                waiter.reason = safeMessage(error);
                waiter.latch.countDown();
            }
            ackWaiters.clear();
            if (helloWaiter != null) helloWaiter.countDown();
            helloWaiter = null;
        }
    }

    private void pruneAssemblies() {
        long now = System.currentTimeMillis();
        assemblies.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private void pruneCompletedTransfers() {
        long now = System.currentTimeMillis();
        completedTransfers.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private boolean sendWire(@NonNull JSONObject frame) {
        try {
            wireQueue.execute(() -> {
                if (!destroyed && physicalReady && !transport.send(frame.toString())) {
                    lastError = "Xiaomi transport rejected frame";
                    Log.w(TAG, lastError);
                } else if (!destroyed && physicalReady) WearDiagnosticStatus.protocolFrame(false);
            });
            return true;
        } catch (RuntimeException rejected) {
            lastError = "TGW/2 wire queue full";
            Log.e(TAG, lastError, rejected);
            return false;
        }
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return String.format(java.util.Locale.US, "%08x", crc.getValue());
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    @Override public void destroy() {
        destroyed = true;
        protocolReady = false;
        physicalReady = false;
        failWaiters(new IllegalStateException("connection destroyed"));
        synchronized (lock) { assemblies.clear(); }
        sendQueue.shutdownNow();
        wireQueue.shutdownNow();
        transport.destroy();
    }

    private static final class AckWaiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean ok;
        volatile String reason = "ACK timeout";
    }

    private static final class Assembly {
        final int total;
        final int totalBytes;
        final String totalCrc32;
        final long expiresAt;
        final byte[][] chunks;
        int received;
        int receivedBytes;
        int nextSeq;
        Assembly(int total, int totalBytes, String totalCrc32, long expiresAt) {
            this.total = total;
            this.totalBytes = totalBytes;
            this.totalCrc32 = totalCrc32;
            this.expiresAt = expiresAt;
            this.chunks = new byte[total][];
        }
    }

    private static final class CompletedTransfer {
        final String totalCrc32;
        final long expiresAt;
        CompletedTransfer(String totalCrc32, long expiresAt) {
            this.totalCrc32 = totalCrc32;
            this.expiresAt = expiresAt;
        }
    }
}
