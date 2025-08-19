// payload/PayloadFactory.java
package com.airoom.secureagent.payload;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.device.DeviceFingerprint;
import com.airoom.secureagent.device.DeviceFingerprintCollector;
import com.airoom.secureagent.log.LogManager;

/** 이벤트 시점에 바로 쓰는 ForensicPayload 생성 헬퍼 */
public class PayloadFactory {
    public static ForensicPayload forEvent(EventType type, String contentId){
        DeviceFingerprint fp = DeviceFingerprintCollector.collect(); // 기존 수집기
        String uid = PayloadManager.boundUserId();
        if (uid == null || uid.isBlank()) uid = LogManager.getUserId();
        return PayloadManager.build(fp, uid, contentId, type);
    }
}
