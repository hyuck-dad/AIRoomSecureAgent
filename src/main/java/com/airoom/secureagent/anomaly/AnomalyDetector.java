package com.airoom.secureagent.anomaly;

import com.airoom.secureagent.log.LogManager;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 실시간 이상행위 탐지기
 *
 * 입력: LogEmitter.emit(...) → AnomalyDetector.consume(LogEvent ev)
 * - 횟수형 이벤트(CAPTURE, STEGO_INSERT): 슬라이딩 윈도 카운트 → 임계치 초과 시 Alert
 * - 지속형 이벤트(RECORDING): 세션 트래커(시작~지속시간) → 기준 초과 시 Alert 1회/세션
 *
 * 튜닝 포인트(기본값 아래 상수):
 * - COUNT_* : 윈도/임계치
 * - RECORDING_* : 지속 기준/비활성 종료 간격
 * - ALERT_COOLDOWN_MS : 동일 키(user+type) 쿨다운(스팸 방지)
 *
 * 스레드-세이프:
 * - 맵은 ConcurrentHashMap, 각 Deque는 동기화 블록으로 보호
 */
public class AnomalyDetector {

    // ===== 1) 파라미터(테스트 값 유지) =====
    private static final long COUNT_WINDOW_CAPTURE_MS     = Duration.ofSeconds(30).toMillis();
    private static final int  COUNT_THRESHOLD_CAPTURE     = 5;

    private static final long COUNT_WINDOW_STEGO_IMAGE_MS = Duration.ofSeconds(60).toMillis();
    private static final int  COUNT_THRESHOLD_STEGO_IMAGE = 10;

    private static final long COUNT_WINDOW_STEGO_PDF_MS   = Duration.ofSeconds(60).toMillis();
    private static final int  COUNT_THRESHOLD_STEGO_PDF   = 10;

    // RECORDING: 30초 이상 지속 시 이상 (테스트 전용)
    private static final long RECORDING_ALERT_AFTER_MS    = Duration.ofSeconds(30).toMillis();
    // RECORDING: 이벤트 끊긴 뒤 세션 종료 간주 간격 (ProcessMonitor 주기보다 크게)
    private static final long RECORDING_INACTIVE_GAP_MS   = Duration.ofSeconds(15).toMillis();

    // 동일 (userId + type) 알림 쿨다운(중복 노이즈 방지)
    private static final long ALERT_COOLDOWN_MS           = Duration.ofSeconds(20).toMillis();

    // ===== 2) 내부 상태 =====
    private static final Map<String, Deque<Long>> countWindows = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastAlertAt         = new ConcurrentHashMap<>();

    // 지속형(녹화) 세션: key = userId
    private static final Map<String, RecordingSession> recordingSessions = new ConcurrentHashMap<>();

    /**
     * suppressedLogged: 이 세션에서 쿨다운으로 **억제 로그**를 1번이라도 남겼는지
     * alerted: 이 세션에서 실제 알림을 보냈는지(세션당 1회 보장)
     */
    private record RecordingSession(
            long startTs,
            long lastSeenTs,
            String source,
            boolean alerted,
            boolean suppressedLogged
    ) {
        RecordingSession touch(long now)         { return new RecordingSession(startTs, now, source, alerted, suppressedLogged); }
        RecordingSession markAlerted()           { return new RecordingSession(startTs, lastSeenTs, source, true, suppressedLogged); }
        RecordingSession markSuppressedLogged()  { return new RecordingSession(startTs, lastSeenTs, source, alerted, true); }
    }

    // ===== 3) 진입점 =====
    public static void consume(LogEvent ev) {
        if (ev == null) return;

        switch (ev.getType()) {
            case CAPTURE      -> handleCountEvent(ev, COUNT_WINDOW_CAPTURE_MS,     COUNT_THRESHOLD_CAPTURE);
            case STEGO_IMAGE  -> handleCountEvent(ev, COUNT_WINDOW_STEGO_IMAGE_MS, COUNT_THRESHOLD_STEGO_IMAGE);
            case STEGO_PDF    -> handleCountEvent(ev, COUNT_WINDOW_STEGO_PDF_MS,   COUNT_THRESHOLD_STEGO_PDF);
            case RECORDING    -> handleRecordingEvent(ev);
            default -> { /* DECODE_* 등은 현재 미사용 */ }
        }
    }

    // ===== 4) 횟수형 =====
    private static void handleCountEvent(LogEvent ev, long windowMs, int threshold) {
        String key = key(ev.getUserId(), ev.getType());
        long now = ev.getTimestamp();
        Deque<Long> q = countWindows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (q) {
            q.addLast(now);
            long cutoff = now - windowMs;
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.removeFirst();

            if (q.size() >= threshold) {
                if (cooldownOk(key, now)) {
                    String sample = shorten(ev.getSource()) + "@" + shorten(ev.getPageOrPath());
                    int count = q.size();

                    AlertSender.sendCountAlert(
                            ev.getUserId(), ev.getType(), count, (int)(windowMs / 1000), sample
                    );
                    lastAlertAt.put(key, now);
                    q.clear();
                } else {
                    long remainSec = remainCooldownSec(key, now);
                    LogManager.writeLog("[ALERT][SUPPRESSED][COOLDOWN] user=" + ev.getUserId()
                            + " type=" + ev.getType()
                            + " count=" + q.size()
                            + " windowSec=" + (windowMs / 1000)
                            + " remainSec=" + remainSec);
                }
            }
        }
    }

    // ===== 5) 지속형(녹화) =====
    private static void handleRecordingEvent(LogEvent ev) {
        String user = ev.getUserId();
        long now = ev.getTimestamp();
        String k = key(user, EventType.RECORDING);

        recordingSessions.compute(user, (u, sess) -> {
            if (sess == null) {
                // 새 세션 시작
                return new RecordingSession(now, now, ev.getSource(), false, false);
            }

            // 세션 연장
            RecordingSession updated = sess.touch(now);

            // 비활성 간격 초과 → 세션 종료로 간주하고 새 세션 시작
            if (now - sess.lastSeenTs() > RECORDING_INACTIVE_GAP_MS) {
                return new RecordingSession(now, now, ev.getSource(), false, false);
            }

            long duration = updated.lastSeenTs() - updated.startTs();

            // (A) 임계 도달 전: 아무 것도 안 함
            if (duration < RECORDING_ALERT_AFTER_MS) {
                return updated;
            }

            // (B) 임계 도달/이후: 세션당 1회 알림 시도 + 필요 시 1회 억제 로그
            if (!updated.alerted()) {
                if (cooldownOk(k, now)) {
                    AlertSender.sendRecordingAlert(user, (int)(duration / 1000), updated.source());
                    lastAlertAt.put(k, now);
                    return updated.markAlerted();
                } else if (!updated.suppressedLogged()) {
                    long remainSec = remainCooldownSec(k, now);
                    LogManager.writeLog("[ALERT][SUPPRESSED][COOLDOWN] user=" + user
                            + " type=RECORDING"
                            + " durationSec=" + (duration / 1000)
                            + " remainSec=" + remainSec);
                    return updated.markSuppressedLogged();
                }
                // 쿨다운 중 억제 로그는 세션당 1회만
                return updated;
            }

            // (C) 이미 알림 보낸 세션이라도, 아직 쿨다운 중이고 suppress 로그를 안 찍었다면 1회만 남김
            if (updated.alerted() && !updated.suppressedLogged() && !cooldownOk(k, now)) {
                long remainSec = remainCooldownSec(k, now);
                LogManager.writeLog("[ALERT][SUPPRESSED][COOLDOWN] user=" + user
                        + " type=RECORDING"
                        + " durationSec=" + (duration / 1000)
                        + " remainSec=" + remainSec);
                return updated.markSuppressedLogged();
            }

            return updated;
        });
    }

    // ===== 6) 헬퍼 =====
    private static String key(String userId, EventType type) {
        return Objects.toString(userId, "unknown") + "|" + type.name();
    }

    private static boolean cooldownOk(String key, long now) {
        Long last = lastAlertAt.get(key);
        return (last == null) || (now - last >= ALERT_COOLDOWN_MS);
    }

    private static long remainCooldownSec(String key, long now) {
        long last = lastAlertAt.getOrDefault(key, 0L);
        long remainMs = ALERT_COOLDOWN_MS - (now - last);
        return Math.max(0, remainMs / 1000);
    }

    private static String shorten(String s) {
        if (s == null) return "-";
        s = s.trim();
        int max = 60;
        return (s.length() <= max) ? s : s.substring(0, max) + "...";
    }
}
