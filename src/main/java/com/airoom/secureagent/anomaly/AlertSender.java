package com.airoom.secureagent.anomaly;

import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.LogManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.airoom.secureagent.payload.ForensicPayload;
import com.airoom.secureagent.payload.PayloadManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 이상행위 알림 전송기

 지금은 동일 라인 로그 채널로만 보냅니다. 서버 측에서 /alert API가 준비되면 이 파일만 교체해서 JSON(암호화)로 POST 하게 바꾸면 끝.

 *
 * 현재는 "사람이 읽기 쉬운 라인 로그"를 HttpLogger로 그대로 전송한다.
 * - 나중에 서버가 /alert 엔드포인트가 준비되면, 여기만 바꿔서 JSON+암호화로 전송 가능.
 */

/**
 * 이상행위 알림 전송기
 *
 * 현재 동작:
 *  - 사람이 읽기 쉬운 라인 로그를 LogManager(파일) + HttpLogger(로컬 /log)로 전송
 *  - 본문 암호화는 HttpLogger 내부에서 처리 (서버 측 LogHandler에서 복호화)
 *
 * TODO(/alert 전환 가이드):
 *  1) 서버에 HTTPS 기반의 전용 엔드포인트가 생기면 (예: POST https://aidt.example.com/alert)
 *     이 클래스에서 HttpLogger 대신 "AlertHttpClient" 같은 전용 클라이언트로 교체한다.
 *
 *  2) 권장 페이로드(JSON) 예시:
 *     {
 *       "ts": 1723098450123,            // epoch ms
 *       "userId": "amy",
 *       "type": "CAPTURE",              // CAPTURE | STEGO_INSERT | RECORDING
 *       "metric": {                     // count형 또는 duration형 중 하나 사용
 *         "count": 7,
 *         "windowSec": 30
 *       },
 *       "durationSec": null,            // RECORDING일 때는 여기 사용 (예: 360)
 *       "source": "ProcessMonitor",     // 수집 소스(선택)
 *       "pageOrPath": "/downloads/file.pdf" // 브라우저 타이틀/페이지/파일경로(선택)
 *     }
 *
 *  3) 전송 보안:
 *     - 기본: HTTPS(TLS) 사용
 *     - 선택: 본문 AES 추가 암호화 → Content-Type을 application/octet-stream 등으로 지정하고
 *            서버에서 복호화 후 JSON 파싱
 *
 *  4) 클라이언트 전송 스케치(참고용):
 *     // String json = buildJson(...);
 *     // String body = CryptoUtil.encrypt(json); // 선택 (서버와 합의 시)
 *     // HttpClient.newHttpClient().send(
 *     //    HttpRequest.newBuilder(URI.create("https://aidt.example.com/alert"))
 *     //      .header("Content-Type","application/octet-stream") // 또는 application/json
 *     //      .POST(HttpRequest.BodyPublishers.ofString(body))
 *     //      .build(),
 *     //    HttpResponse.BodyHandlers.discarding()
 *     // );
 *
 *  5) 네트워크 장애 시:
 *     - 실패하면 OfflineQueue(파일/SQLite)에 적재 후 백오프 재전송 워커가 주기적으로 재시도
 *
 * 지금은 운영 서버 준비 전이므로 현 구현(로컬 /log + HttpLogger) 유지.
 */
public class AlertSender {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

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
    /** 포렌식 이벤트 전송 (DB 저장 없이 서버에서 실시간 검증만) */
    public static void sendForensicEvent(ForensicPayload p) {
        String encPayloadB64 = PayloadManager.encryptPayload(p);       // AES-Base64
        String token = PayloadManager.makeVisibleToken(p, 12);         // HMAC-hex 12

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("encPayload", encPayloadB64);
        body.put("agentTs", p.ts());
        body.put("agentVer", p.app());

        String json = GSON.toJson(body);
        // /event 엔드포인트로 JSON 전송 (본문은 CryptoUtil로 추가 암호화)
        HttpLogger.sendJson("/event", json, true);
    }

    private static String safe(String s) { return (s == null) ? "-" : s; }
}
