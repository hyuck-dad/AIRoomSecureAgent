package com.airoom.secureagent.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.StringJoiner;
// 복합 Device ID 해시 생성
// 전략 : 여러 신호를 정규화해 해시로 결합 → 복합 Device ID를 만들고,
// 원시 신호에는 **품질 태그(예: mac=RANDOM/VM/GOOD)**를 붙여 판단 근거를 남긴다.
public class DeviceIdGenerator {

    // 환경변수 AIDT_DEVICE_SALT 가 있으면 우선, 없으면 기본값
    private static final String DEFAULT_SALT = "AIDT_SECURE_AGENT_SALT_DEV";

    public static String compute(DeviceFingerprint fp) {
        String salt = System.getenv().getOrDefault("AIDT_DEVICE_SALT", DEFAULT_SALT);

        StringJoiner sj = new StringJoiner("|");
        sj.add(n(fp.machineGuid()));
        sj.add(n(fp.primaryMac()));
        sj.add(n(fp.biosSerial()));
        sj.add(n(fp.baseboardSerial()));
        sj.add(n(fp.diskSerial()));
        sj.add(n(fp.cpuId()));
        sj.add(n(fp.macQuality() == null ? "NONE" : fp.macQuality().name()));
        sj.add(fp.vmSuspect() ? "VM" : "PHYSICAL");
        sj.add(salt);

        return sha256Hex(sj.toString()).substring(0, 20); // 20 hex (80bit) 토큰
    }

    private static String n(String s) { return s == null ? "-" : s.trim().toUpperCase(); }

    /*
     해시(SHA-256) 토큰은 “식별자” 용도, AES는 “내용 보관/복호화” 용도라서 쓰임새가 다름.

        왜 SHA-256(해시) 토큰인가?
        비가역성(One-way): 원본(MachineGuid, MAC 등)을 복원할 수 없어요. 식별은 되지만 원문 노출은 막는 게 목적이라 해시에 딱 맞습니다.
        결정적·짧은 길이: 같은 입력 → 항상 같은 출력(비교·조인 쉬움). 20자 hex(=80bit)로 짧게 보여줄 수 있어 UI/로그에 적합.
        키 관리 불필요: 해시는 “검증용 식별자”라 복호화 키가 필요 없습니다. AES를 식별자에 쓰면 키·IV·모드 관리가 따라붙고, 잘못 쓰면 비결정적(비교가 어려움) 혹은 보안취약(ECB) 문제가 생깁니다.
        개인정보 최소화: 원시 하드웨어 값 대신 해시된 지문만 저장/표시—유출 시 리스크↓.
        80bit(20 hex)로 줄여도 충돌 확률은 매우 낮습니다. 예를 들어 1천만 대 규모에서도 대략 4×10⁻¹¹ 수준. 1억 대도 ~4×10⁻⁹, 10억 대도 ~4×10⁻⁷ 정도라 실무에서 충분합니다. (규모가 훨씬 커질 우려가 있으면 24~32 hex로 늘리면 돼요.)

        그럼 AES는 언제?
        복원 가능해야 하는 데이터(예: uid, deviceId, contentId, ts…가 들어 있는 포렌식 payload)를 암호화해서 보관/전송할 때.
        즉, 워터마크에 “짧은 토큰(해시/서명)”을 노출하고, 메타데이터/로그에는 AES로 암호화한 전체 payload를 숨겨 넣는 구조가 자연스럽습니다.
        토큰을 해시로 한 이유 vs AES로 토큰 만들기?
        AES로 “토큰처럼” 만들려면 보통 IV가 필요해 비결정적이 됩니다(비교가 어려움). IV를 고정하면 보안상 좋지 않죠. ECB는 결정적이지만 금지 수준입니다.
        반면 **SHA-256(+salt)**는 결정적이면서 짧고, 키관리 부담이 없음. 식별자 목적에 최적.
     */

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
