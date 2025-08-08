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

    /* ============================
     * 1) 파라미터(초기값)
     * ============================ */
    // CAPTURE: 30초 내 5회 이상
    private static final long COUNT_WINDOW_CAPTURE_MS = Duration.ofSeconds(30).toMillis();
    private static final int  COUNT_THRESHOLD_CAPTURE  = 5;

    // STEGO_IMAGE: 60초 내 10회 이상
    private static final long COUNT_WINDOW_STEGO_IMAGE_MS = Duration.ofSeconds(60).toMillis();
    private static final int  COUNT_THRESHOLD_STEGO_IMAGE  = 10;

    // STEGO_PDF: 60초 내 10회 이상 (원하면 값 분리해서 튜닝)
    private static final long COUNT_WINDOW_STEGO_PDF_MS = Duration.ofSeconds(60).toMillis();
    private static final int  COUNT_THRESHOLD_STEGO_PDF  = 10;

    // RECORDING: 5분 이상 지속 시 이상
    private static final long RECORDING_ALERT_AFTER_MS = Duration.ofMinutes(5).toMillis();
    // RECORDING: 이벤트 끊긴 뒤 세션 종료로 간주할 gap
    private static final long RECORDING_INACTIVE_GAP_MS = Duration.ofSeconds(30).toMillis();

    // 동일 (userId + type) 알림 쿨다운(중복 노이즈 방지)
    private static final long ALERT_COOLDOWN_MS = Duration.ofMinutes(1).toMillis();

    /* ============================
     * 2) 내부 상태
     * ============================ */
    // 횟수형: key = userId|type  → 최근 타임스탬프 큐
    private static final Map<String, Deque<Long>> countWindows = new ConcurrentHashMap<>();
    // 마지막 알림 시각: key = userId|type
    private static final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    // 지속형(녹화) 세션: key = userId
    private static final Map<String, RecordingSession> recordingSessions = new ConcurrentHashMap<>();

    private record RecordingSession(long startTs, long lastSeenTs, String source, boolean alerted) {
        RecordingSession touch(long now) { return new RecordingSession(startTs, now, source, alerted); }
        RecordingSession markAlerted()   { return new RecordingSession(startTs, lastSeenTs, source, true); }
    }

    /* ============================
     * 3) 진입점
     * ============================ */
    public static void consume(LogEvent ev) {
        if (ev == null) return;

        switch (ev.getType()) {
            case CAPTURE -> handleCountEvent(ev, COUNT_WINDOW_CAPTURE_MS, COUNT_THRESHOLD_CAPTURE);
            case STEGO_IMAGE -> handleCountEvent(ev, COUNT_WINDOW_STEGO_IMAGE_MS, COUNT_THRESHOLD_STEGO_IMAGE);
            case STEGO_PDF   -> handleCountEvent(ev, COUNT_WINDOW_STEGO_PDF_MS,   COUNT_THRESHOLD_STEGO_PDF);
            case RECORDING -> handleRecordingEvent(ev);
            default -> {
                // DECODE_SUCCESS / DECODE_FAIL 등은 현재 탐지에 사용하지 않음
            }
        }
    }

    /* ============================
     * 4) 횟수형 이벤트 처리
     * ============================ */
    private static void handleCountEvent(LogEvent ev, long windowMs, int threshold) {
        String key = key(ev.getUserId(), ev.getType());
        long now = ev.getTimestamp();
        Deque<Long> q = countWindows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (q) {
            q.addLast(now);
            // 윈도 바깥 제거
            long cutoff = now - windowMs;
            while (!q.isEmpty() && q.peekFirst() < cutoff) q.removeFirst();

            if (q.size() >= threshold && cooldownOk(key, now)) {
                // 샘플 몇 개만 가져다 로그에 실어주기(소스/경로)
                String sample = shorten(ev.getSource()) + "@" + shorten(ev.getPageOrPath());
                int count = q.size();

                // Alert 전송
                AlertSender.sendCountAlert(
                        ev.getUserId(),
                        ev.getType(),
                        count,
                        (int) (windowMs / 1000),
                        sample
                );

                // 중복 알림 억제: 큐를 비우거나, 쿨다운만 갱신하여 중복 방지
                lastAlertAt.put(key, now);
                q.clear();
            }
        }
    }

    /* ============================
     * 5) 지속형(녹화) 이벤트 처리
     * ============================ */
    private static void handleRecordingEvent(LogEvent ev) {
        String user = ev.getUserId();
        long now = ev.getTimestamp();

        recordingSessions.compute(user, (u, sess) -> {
            if (sess == null) {
                // 새로운 세션 시작
                return new RecordingSession(now, now, ev.getSource(), false);
            }

            // 기존 세션 업데이트(lastSeen 갱신)
            RecordingSession updated = sess.touch(now);

            // 비활성 간격 초과 → 세션 종료로 간주하고 새 세션 시작
            if (now - sess.lastSeenTs() > RECORDING_INACTIVE_GAP_MS) {
                return new RecordingSession(now, now, ev.getSource(), false);
            }

            // 기준 시간 초과 && 아직 알림 안 보냄 && 쿨다운 확인
            long duration = updated.lastSeenTs() - updated.startTs();
            if (duration >= RECORDING_ALERT_AFTER_MS && !updated.alerted() && cooldownOk(key(user, EventType.RECORDING), now)) {
                AlertSender.sendRecordingAlert(
                        user,
                        (int) (duration / 1000),
                        updated.source()
                );
                lastAlertAt.put(key(user, EventType.RECORDING), now);
                return updated.markAlerted();
            }
            return updated;
        });
    }

    /* ============================
     * 6) 헬퍼
     * ============================ */
    private static String key(String userId, EventType type) {
        return Objects.toString(userId, "unknown") + "|" + type.name();
    }

    private static boolean cooldownOk(String key, long now) {
        Long last = lastAlertAt.get(key);
        return (last == null) || (now - last >= ALERT_COOLDOWN_MS);
    }

    private static String shorten(String s) {
        if (s == null) return "-";
        s = s.trim();
        int max = 60;
        return (s.length() <= max) ? s : s.substring(0, max) + "...";
    }

    /* ============================
     * 7) (옵션) 동적 파라미터 주입을 원할 때
     * - System.getProperty(...) 로 덮어쓰기 구현 가능
     * - 예: -Daidt.count.capture.windowSec=45 등
     * 지금은 단순 상수로 두고, 운영 들어가면 config 클래스로 분리 권장.
     * ============================ */

    // 필요 시, 주기적으로 오래된 recording 세션 정리하는 GC 스레드도 붙일 수 있음.
}
