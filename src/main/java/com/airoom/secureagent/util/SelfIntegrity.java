package com.airoom.secureagent.util;

import java.nio.file.*;
import java.security.MessageDigest;

// 무결성 점검
// “무결성”은 바이너리 위·변조 탐지
// 그 최소 증거가 “실행 중인 바이너리의 해시를 계산해서 보고한다”

// “위·변조는 어떻게 탐지하나요?”
// → “에이전트가 자기 자신을 SHA-256으로 해시해서 서버에 보고합니다. 서버는 CI에서 만든 화이트리스트랑 대조

// SHA-256 해시는 “그 바이너리 파일의 바이트”에 1:1로 대응
// 한 번 package(build)해서 나온 산출물(예: 핵심 JAR/EXE)의 바이트가 같다면 모든 PC에서 동일한 해시가 나오고,
//→ 코드를 바꾸거나 빌드 산출물의 바이트가 바뀌면(리소스, 매니페스트, 타임스탬프 등) 해시도 달라져서 BE 화이트리스트에 등록한 값과 불일치하게 된다

// 무결성(바이너리 위변조 탐지): 실행 파일의 SHA-256을 서버의 화이트리스트와 대조 → 변조/교체 탐지.
// 전송 무결성(HMAC): 이벤트/로그 요청이 정말 에이전트에서 왔는지(재전송·위조 방지) 확인. 목적이 다르다.
// 지금 목표가 “깔끔한 무결성 마무리”라면 바이너리 해시 대조만으로 충분히 납득

// 발표에서 이렇게 말하면 된다
//“에이전트는 기동 시 자기 자신을 SHA-256으로 해시하고 /status·/verify로 보고합니다.
//서버는 CI에서 생성한 ‘버전→SHA’ 화이트리스트를 갖고 있고, 그 값과 대조해 변조 여부를 판정

public final class SelfIntegrity {
    private static volatile String shaHex = "UNKNOWN";
    private static volatile Path binaryPath;

    public static synchronized void initOnce(Class<?> anchor) {
        if (!"UNKNOWN".equals(shaHex)) return;
        try {
            // 1) 실행 바이너리 힌트가 있으면 우선
            String hint = System.getProperty("aidt.binary.path", System.getenv("AIDT_BINARY_PATH"));
            Path p = (hint != null && !hint.isBlank()) ? Paths.get(hint)
                    : Paths.get(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(p)) {
                // IDE 실행 등 디렉토리일 수 있으니 힌트가 없으면 그대로 UNKNOWN 유지
                binaryPath = p;
                shaHex = "DEV-UNKNOWN";
                return;
            }
            binaryPath = p;
            shaHex = sha256Hex(Files.readAllBytes(p));
        } catch (Exception e) {
            shaHex = "UNKNOWN";
        }
    }
    public static String sha256() { return shaHex; }

    private static String sha256Hex(byte[] d) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] x = md.digest(d);
        StringBuilder sb = new StringBuilder(x.length * 2);
        for (byte b : x) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
