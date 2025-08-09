// payload/PayloadFactory.java
package com.airoom.secureagent.payload;

import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.device.DeviceFingerprintCollector;
import com.airoom.secureagent.log.LogManager;

/** 이벤트 시점에 바로 쓰는 ForensicPayload 생성 헬퍼 */
public class PayloadFactory {
    public static ForensicPayload forEvent(EventType action, String contentIdOrPath) {
        var fp  = DeviceFingerprintCollector.collect();                 // 단말 지문 수집
        var uid = (LogManager.getUserId() == null || LogManager.getUserId().isBlank())
                ? "-" : LogManager.getUserId();
        var cid = (contentIdOrPath == null || contentIdOrPath.isBlank())
                ? "-" : contentIdOrPath;
        return PayloadManager.build(fp, uid, cid, action);              // 표준 빌드
    }
}
