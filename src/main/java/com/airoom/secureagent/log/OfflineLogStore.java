package com.airoom.secureagent.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface OfflineLogStore {
    void append(String message) throws IOException;

    List<Path> listReady(int max) throws IOException;

    String read(Path file) throws IOException;

    /** ready -> sending 으로 원자적 이동, 이동된 새 경로 반환 */
    Path markSending(Path readyFile) throws IOException;

    /** 전송 성공 시 sending 파일 삭제 */
    void markDone(Path sendingFile) throws IOException;

    /** 전송 실패 시 sending -> ready 로 원복 */
    void markFailed(Path sendingFile) throws IOException;

    /** 비정상 종료 등으로 sending에 남은 파일을 ready로 되돌림 */
    void recoverOrphanedSending() throws IOException;
}
