package com.airoom.secureagent.anomaly;

import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.LogManager;

/**
 * 이상행위 알림 전송기

 지금은 동일 라인 로그 채널로만 보냅니다. 서버 측에서 /alert API가 준비되면 이 파일만 교체해서 JSON(암호화)로 POST 하게 바꾸면 끝.

 *
 * 현재는 "사람이 읽기 쉬운 라인 로그"를 HttpLogger로 그대로 전송한다.
 * - 나중에 서버가 /alert 엔드포인트가 준비되면, 여기만 바꿔서 JSON+암호화로 전송 가능.
 */
public class AlertSender {

    /** 횟수형 임계치 초과 알림 */
    public static void sendCountAlert(String userId, EventType type, int count, int windowSec, String sample) {
        String line = "[ALERT] " +
                "user=" + safe(userId) +
                " type=" + type.name() +
                " count=" + count +
                " within=" + windowSec + "s" +
                (sample != null ? " sample=" + sample : "");

        // 파일 로그 + 서버 전송
        LogManager.writeLog(line);
        HttpLogger.sendLog(line);
    }

    /** 녹화 지속 알림 */
    public static void sendRecordingAlert(String userId, int durationSec, String source) {
        String line = "[ALERT] " +
                "user=" + safe(userId) +
                " type=RECORDING duration=" + durationSec + "s" +
                (source != null ? " source=" + source : "");

        LogManager.writeLog(line);
        HttpLogger.sendLog(line);
    }

    private static String safe(String s) {
        return (s == null) ? "-" : s;
    }
}
