package com.airoom.secureagent.anomaly;

import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.LogManager;
import com.airoom.secureagent.payload.ForensicPayload;


public class LogEmitter {

    /**
     * 인적 친화적 로그(line) + 구조화 이벤트(ev)를 동시에 발행
     문자 로그는 기존처럼 남기되, 여기서 중앙집중으로 LogManager/HttpLogger/AnomalyDetector를 한 번에 호출하게.
     */
    public static void emit(LogEvent ev, String humanLine) {
        // 1) 파일 로그 + (현재는) 서버 전송
        LogManager.writeLog(humanLine);
        HttpLogger.sendLog(humanLine);

        // 2) 실시간 이상행위 분석용 이벤트 스트림
        AnomalyDetector.consume(ev);  // 다음 단계에서 구현 (현재는 빈 메서드로 만들어도 OK)
    }

    /** 사람이 읽는 로그도 남기고, 서버 실시간 검증도 동시에 수행 */
    public static void emitForensic(ForensicPayload p, String humanLine) {
        LogManager.writeLog(humanLine);
        HttpLogger.sendLog(humanLine);
        AlertSender.sendForensicEvent(p);
    }
}

