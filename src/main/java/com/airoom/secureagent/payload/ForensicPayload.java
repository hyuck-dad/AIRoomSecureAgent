package com.airoom.secureagent.payload;

import com.airoom.secureagent.anomaly.EventType;

/**
 * 포렌식/추적에 필요한 최소 정보를 담는 전송용 DTO.
 * - JSON 직렬화 후 AES로 암호화하여(기존 CryptoUtil) 메타데이터/로그/서버 전송 본문에 사용.
 * - 화면 워터마크엔 본문 전체가 아닌, 이 객체로부터 생성한 '짧은 HMAC 토큰'만 노출.
 */
public record ForensicPayload(
        int ver,               // 페이로드 스키마 버전 (필드 구조 변경 대비)
        String app,            // 에이전트 앱 버전/식별 (예: "SecureAgent/0.9.0")
        String uid,            // 사용자 ID (세션/로그인 연동)
        String deviceId,       // 복합 Device ID (SHA-256 해시 토큰)
        String macQuality,     // MAC 품질 태그: NONE/RANDOM/VM/GOOD
        boolean vm,            // VM/가상 NIC/랜덤 MAC 의심 여부
        String contentId,      // 컨텐츠/리소스 식별자 (없으면 "-" 권장)
        EventType action,      // 이벤트 유형 (CAPTURE/RECORDING/STEGO_IMAGE/STEGO_PDF/DECODE_*)
        String ts              // ISO_OFFSET_DATE_TIME (예: 2025-08-09T15:21:33+09:00)
) {}
