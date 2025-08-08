package com.airoom.secureagent.anomaly;

public enum EventType {
    CAPTURE,           // 키보드 PrintScreen, 캡처 툴 실행 등 "횟수형"
    RECORDING,         // 녹화 툴(OBS/NVIDIA/GameBar) - "지속형(세션)"
    STEGO_INSERT,      // 스테고 삽입(이미지/PDF) - 다운로드 유사 "횟수형"
    DECODE_SUCCESS,    // (옵션) 디코딩 성공 이벤트
    DECODE_FAIL        // (옵션) 디코딩 실패 이벤트
}
