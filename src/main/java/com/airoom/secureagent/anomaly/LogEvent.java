package com.airoom.secureagent.anomaly;

public class LogEvent {
    private final long timestamp;
    private final String userId;
    private final EventType type;
    private final String source;      // "PrintScreen" / "SnippingTool.exe" / "obs64.exe" / "png|jpeg|pdf" 등
    private final String pageOrPath;  // 브라우저 타이틀 or 파일 경로
    private final String note;        // 추가 정보(선택)

    public LogEvent(long timestamp, String userId, EventType type, String source, String pageOrPath, String note) {
        this.timestamp = timestamp;
        this.userId = userId;
        this.type = type;
        this.source = source;
        this.pageOrPath = pageOrPath;
        this.note = note;
    }

    public static LogEvent of(EventType type, String source, String pageOrPath, String note, String userId) {
        return new LogEvent(System.currentTimeMillis(), userId, type, source, pageOrPath, note);
    }

    public long getTimestamp() { return timestamp; }
    public String getUserId() { return userId; }
    public EventType getType() { return type; }
    public String getSource() { return source; }
    public String getPageOrPath() { return pageOrPath; }
    public String getNote() { return note; }
}
