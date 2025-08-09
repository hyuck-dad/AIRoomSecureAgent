package com.airoom.secureagent.device;
// MAC 품질 태그 enum
public enum MacQuality {
    NONE,        // 수집 실패
    RANDOM,      // Locally Administered (랜덤/스푸핑 의심)
    VM,          // 가상 NIC 의심
    GOOD         // 물리 NIC로 보이며 랜덤 비트 아님
}
