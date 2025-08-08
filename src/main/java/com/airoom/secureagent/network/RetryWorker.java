package com.airoom.secureagent.network;

import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.OfflineLogStore;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 스풀에 쌓인 로그를 일정 주기로 재전송 (지수 백오프) */
public class RetryWorker {

    private final OfflineLogStore store;
    private final int batchSize;
    private final ScheduledExecutorService ses;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile int nextDelaySec = 30;
    private static final int MIN_DELAY = 5;
    private static final int MAX_DELAY = 300;

    private ScheduledFuture<?> future;

    public RetryWorker(OfflineLogStore store) { this(store, 200); }
    public RetryWorker(OfflineLogStore store, int batchSize) {
        this.store = store;
        this.batchSize = batchSize;
        this.ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retry-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() { scheduleNext(MIN_DELAY); }
    public void shutdown() { if (future != null) future.cancel(false); ses.shutdownNow(); }
    public void flushNow() { scheduleNext(0); }

    private synchronized void scheduleNext(int delaySec) {
        if (future != null) future.cancel(false);
        future = ses.schedule(this::cycle, delaySec, TimeUnit.SECONDS);
    }

    private void cycle() {
        boolean anyFailed = false;
        boolean anyTried = false;

        if (!running.compareAndSet(false, true)) { scheduleNext(nextDelaySec); return; }
        try {
            List<Path> ready = store.listReady(batchSize);
            if (!ready.isEmpty()) {
                for (Path r : ready) {
                    Path sending = store.markSending(r);
                    String body = store.read(sending);
                    anyTried = true;

                    boolean ok = HttpLogger.sendLog(body); // HttpLogger 내부가 네트워크 암호화 수행
                    if (ok) {
                        store.markDone(sending);
                    } else {
                        anyFailed = true;
                        store.markFailed(sending);
                        break; // 네트워크 불능으로 판단하고 다음 주기로
                    }
                }
            }
        } catch (Exception e) {
            anyFailed = true;
            e.printStackTrace();
        } finally {
            if (!anyTried) nextDelaySec = 30;
            else if (anyFailed) nextDelaySec = Math.min(Math.max(nextDelaySec * 2, MIN_DELAY), MAX_DELAY);
            else nextDelaySec = 30;

            running.set(false);
            scheduleNext(nextDelaySec);
        }
    }
}
