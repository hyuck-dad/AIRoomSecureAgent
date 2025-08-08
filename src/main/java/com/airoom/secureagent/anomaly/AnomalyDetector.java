package com.airoom.secureagent.anomaly;

import com.airoom.secureagent.log.HttpLogger;

import java.util.*;

public class AnomalyDetector {

    private static final int THRESHOLD_COUNT = 3;      // 감지 기준: X회 이상
    private static final long THRESHOLD_WINDOW_MS = 5000; // 감지 기준: Y밀리초 이내

    private static final Map<AnomalyEventType, Deque<Long>> eventMap = new HashMap<>();

    // 이벤트 기록
    public static void recordEvent(AnomalyEventType type) {
        long now = System.currentTimeMillis();
        eventMap.putIfAbsent(type, new ArrayDeque<>());

        Deque<Long> timestamps = eventMap.get(type);
        timestamps.addLast(now);

        // 오래된 이벤트 제거
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > THRESHOLD_WINDOW_MS) {
            timestamps.pollFirst();
        }

        // 이상 행위 여부 판단
        if (timestamps.size() >= THRESHOLD_COUNT) {
            String log = "[이상 행위 감지] 이벤트: " + type + ", 횟수: " + timestamps.size();
            HttpLogger.sendLog(log);  // 추후: 암호화됨
            System.out.println(log);

            timestamps.clear(); // 중복 탐지 방지
        }
    }

    // 다음 단계에서: userId별 {CAPTURE/STEGO_INSERT} → Deque<Long> (시간 윈도)
    //               userId별 RECORDING → 상태머신(시작~지속시간)
    public static void consume(LogEvent ev) {
        // TODO: 구현 예정
        // 1) 메모리 큐 업데이트
        // 2) 임계치 도달 시 이상 알림 생성
        // 3) dedupeId 생성 후 HttpLogger.sendLog(...) or 전용 AlertSender로 전송
    }
}
