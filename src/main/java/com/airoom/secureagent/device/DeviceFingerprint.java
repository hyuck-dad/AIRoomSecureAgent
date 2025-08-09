package com.airoom.secureagent.device;

import java.util.List;
// 수집 결과 DTO
public record DeviceFingerprint(
        String machineGuid,
        String primaryMac,
        List<String> macList,
        String biosSerial,
        String baseboardSerial,
        String diskSerial,
        String cpuId,
        MacQuality macQuality,
        boolean vmSuspect
) {}
