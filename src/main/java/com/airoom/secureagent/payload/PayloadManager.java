package com.airoom.secureagent.payload;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.device.DeviceFingerprint;
import com.airoom.secureagent.device.DeviceIdGenerator;
import com.airoom.secureagent.util.CryptoUtil;     // 기존 AES 유틸 (encrypt(String)->Base64 가정)
import com.airoom.secureagent.util.TokenUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 포렌식 페이로드 생성/직렬화/암호화/토큰화 오케스트레이션.
 * - JSON 직렬화: Gson
 * - 본문 암호화: CryptoUtil.encrypt(json) -> Base64
 * - 표시용 토큰: HMAC-SHA256(canonical) 앞 10~12자
 */
public class PayloadManager {

    private static final String APP_ID =
            System.getenv().getOrDefault("AIDT_APP_ID", "SecureAgent/0.9.0");

    // 운영에서는 서버에서만 보관/계산 권장. 개발 편의를 위해 에이전트에도 임시 주입 가능.
    private static final String TOKEN_SECRET =
            System.getenv().getOrDefault("AIDT_TOKEN_SECRET", "DEV_TOKEN_SECRET");

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    /**
     * 필수 필드만 받아서 페이로드를 구성한다.
     * @param fp        단말 지문(수집 결과)
     * @param uid       사용자 ID
     * @param contentId 컨텐츠 식별자 (없으면 "-" 권장)
     * @param action    이벤트 유형 (EventType)
     */

    private static volatile String BOUND_UID = "unknown";
    private static volatile String BOUND_JWT = null;

    public static void bindUser(String uid, String jwt){
        if (uid != null && !uid.isBlank()) BOUND_UID = uid;
        BOUND_JWT = jwt;
    }
    public static String boundUserId(){ return BOUND_UID; }
    public static String boundJwt(){ return BOUND_JWT; }
    // build()에서 uid가 비어있으면 바운드된 사용자로 대체
    public static ForensicPayload build(DeviceFingerprint fp, String uid,
                                        String contentId, EventType action) {
        String useUid = (uid == null || uid.isBlank() || "-".equals(uid))
                ? (BOUND_UID != null ? BOUND_UID : "-")
                : uid;
        String ts = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return new ForensicPayload(
                1,                                  // ver
                APP_ID,                             // app
                useUid,                                // uid
                DeviceIdGenerator.compute(fp),      // deviceId (20 hex)
                fp.macQuality() == null ? "NONE" : fp.macQuality().name(), // macQuality
                fp.vmSuspect(),                     // vm
                (contentId == null || contentId.isBlank()) ? "-" : contentId,
                action,
                ts
        );
    }

    /** JSON 직렬화 (Gson) */
    public static String toJson(ForensicPayload p) {
        return GSON.toJson(p);
    }

    /** AES 암호화 → Base64 (기존 CryptoUtil 가정) */
    public static String encryptPayload(ForensicPayload p) {
        try {
            return CryptoUtil.encrypt(toJson(p));
        } catch (Exception e) {
            throw new RuntimeException("Payload 암호화(Base64) 실패", e);
        }
    }

    /** AES 암호화 → byte[] (LSB 등 바이트 삽입용) */
    public static byte[] encryptPayloadBytes(ForensicPayload p) {
        try {
            return CryptoUtil.encryptToBytes(toJson(p));
        } catch (Exception e) {
            throw new RuntimeException("Payload 암호화(byte[]) 실패", e);
        }
    }

    public static String decryptPayloadFromBase64(String encB64) {
        try {
            return CryptoUtil.decrypt(encB64);
        } catch (Exception e) {
            throw new RuntimeException("Payload 복호화(Base64) 실패", e);
        }
    }

    public static String decryptPayloadFromBytes(byte[] enc) {
        try {
            return CryptoUtil.decryptFromBytes(enc);
        } catch (Exception e) {
            throw new RuntimeException("Payload 복호화(byte[]) 실패", e);
        }
    }


    /**
     * 화면 워터마크 등 '표시용 짧은 토큰' 생성.
     * - 결정성 유지: 같은 이벤트 → 같은 canonical → 같은 토큰
     * - canonical 구성 요소는 필요 시 버전업 가능 (ver2 등)
     */
    public static String makeVisibleToken(ForensicPayload p, int hexLen) {
        String canonical = canonicalString(p);
        return TokenUtil.hmacHex(canonical, TOKEN_SECRET, hexLen);
    }

    /** 서버/에이전트가 동일하게 구성해야 하는 canonical 문자열 정의 */
    public static String canonicalString(ForensicPayload p) {
        // ver|app|uid|deviceId|contentId|action|ts
        return p.ver() + "|" + n(p.app()) + "|" + n(p.uid()) + "|" + n(p.deviceId()) + "|"
                + n(p.contentId()) + "|" + (p.action() == null ? "-" : p.action().name())
                + "|" + n(p.ts());
    }

    private static String n(String s) { return (s == null) ? "-" : s; }
}
