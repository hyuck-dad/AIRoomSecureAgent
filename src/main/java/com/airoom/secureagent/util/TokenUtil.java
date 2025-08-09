package com.airoom.secureagent.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 표시용 짧은 토큰(HMAC) 생성 유틸.
 * - '복호화' 대상이 아님 (검증/위변조 방지 목적의 서명)
 * - 서버는 같은 canonical 문자열과 같은 secret으로 재계산하여 진위를 확인.
 */
public class TokenUtil {

    /**
     * HMAC-SHA256 결과를 hex로 만들고 앞자리만 잘라서 반환.
     * @param canonical 토큰 생성의 기준 문자열 (결정성 유지)
     * @param secret    서버/에이전트 공통 비밀키 (운영에선 반드시 서버만 보관)
     * @param hexLen    잘라낼 길이 (워터마크는 8~12 권장)
     */
    public static String hmacHex(String canonical, String secret, int hexLen) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));

            int n = Math.max(4, Math.min(hexLen, sb.length()));
            return sb.substring(0, n);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 생성 오류", e);
        }
    }
}
