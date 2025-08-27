package com.airoom.secureagent.monitor;

import com.airoom.secureagent.SecureAgentMain;
import com.airoom.secureagent.log.HttpLogger;
import com.airoom.secureagent.log.LogManager;
import com.airoom.secureagent.steganography.ImageStegoWithWatermarkEncoder;
import com.airoom.secureagent.steganography.PdfStegoWithWatermarkEncoder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.airoom.secureagent.anomaly.EventType;
import com.airoom.secureagent.anomaly.LogEmitter;
import com.airoom.secureagent.anomaly.LogEvent;
import com.airoom.secureagent.payload.PayloadFactory;
import com.airoom.secureagent.payload.PayloadManager;
import com.airoom.secureagent.payload.ForensicPayload;
import com.airoom.secureagent.anomaly.AlertSender;

import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;

import static java.nio.file.StandardOpenOption.WRITE;
/**
 * 확장자별로 Encoder 호출 → 같은 경로/이름으로 덮어쓰기.
 * PNG/JPG : tEXt or APP1   /  PDF : XMP
 */
public class StegoDispatcher {

    private static volatile float WATERMARK_OPACITY = 0.5f;

    public static float getEmbedOpacity() { return WATERMARK_OPACITY; }
    public static void setEmbedOpacity(float v) {
        WATERMARK_OPACITY = Math.max(0f, Math.min(1f, v));
    }

    /** 최근 N초 안에 처리한 파일을 기억해 중복 호출 차단 (30 s 로 확대) */
    private static final Cache<String, Boolean> recent = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();

    private static final ConcurrentMap<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /** 락이 풀릴 때까지 재시도용 단일 스케줄러 */
    private static final ScheduledExecutorService retryExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stego-retry"); t.setDaemon(true); return t;
            });

    public static void process(Path file) {
        String abs = file.toAbsolutePath().toString();

        // GlobalWatcher 에서 MODIFY 감지 때문에, 지금 tmp+atomic move 방식때문에 계속 해서 MODIFY가 감지되어서
        // 무한 루프에 빠지게 됨. 그래서 쓰기 락 확보 시도 해서, 아직 쓰기 중이면 Retry
        // 쓰기 락 확보 시도
        // 0) 먼저 쓰기 가능 여부 확인 (락 걸려 있으면 'recent' 체크/등록 없이 재시도)
        if (!isWritable(file)) {
            int n = retryCounts.merge(abs, 1, Integer::sum);
            if (n <= 10) { // 최대 10회, 20초 정도 재시도
                LogManager.writeLog("[Stego] file locked, retry " + n + " → " + abs);
                retryExec.schedule(() -> process(file), 2, TimeUnit.SECONDS);
            } else {
                LogManager.writeLog("[Stego] give up after retries → " + abs);
                retryCounts.remove(abs);
            }
            return;
        }
        retryCounts.remove(abs); // 락 해제됨


        // 1) 이제 중복 차단
        if (recent.getIfPresent(abs) != null) return;
        recent.put(abs, Boolean.TRUE);

        // 2) 이미 태깅? 이미 삽입?
        if (AlreadyTaggedChecker.isTagged(file)) {
            LogManager.writeLog("[Stego] 이미 삽입됨 – skip : " + file);
            return;
        }

        // 3) 실제 삽입
        boolean ok = false;
        boolean isImage = isImage(file);
        boolean isPdf   = isPdf(file);
        try {
            if (isImage) {
                // 이미지 이벤트용 포렌식 페이로드 생성
                ForensicPayload p = PayloadFactory.forEvent(EventType.STEGO_IMAGE, abs);
                String encB64 = PayloadManager.encryptPayload(p);                 // AES-Base64
                String token  = PayloadManager.makeVisibleToken(p, 12);           // HMAC 12자
                String wmText = "AIROOM" + token + " " + PayloadManager.boundUserId() + " " + p.ts();                   // 화면/파일 표시용

                ok = ImageStegoWithWatermarkEncoder.encodeEncrypted(
                        abs, abs, encB64, wmText, WATERMARK_OPACITY);

                if (ok) AlertSender.sendForensicEvent(p);                         //  서버 실시간 검증 전송
            } else if (isPdf) {
                //  PDF 이벤트용 포렌식 페이로드 생성
                ForensicPayload p = PayloadFactory.forEvent(EventType.STEGO_PDF, abs);
                String encB64 = PayloadManager.encryptPayload(p);
                String token  = PayloadManager.makeVisibleToken(p, 12);
                String wmText = "AIROOM" + token + " " + PayloadManager.boundUserId() + " " + p.ts();

                ok = PdfStegoWithWatermarkEncoder.embedEncrypted(
                        abs, abs, encB64, wmText, WATERMARK_OPACITY);

                if (ok) AlertSender.sendForensicEvent(p);                         //  서버 실시간 검증 전송
            }
        } catch (Exception ex) {
            LogManager.writeLog("[Stego] 예외 → " + file + " : " + ex);
        }

        // 4) 로그 & 테스트 디코딩, 이벤트 발행 & 디코딩 확인
        if (ok) {
            String log = "[Stego] 삽입 완료 → " + file;

            EventType type = isImage ? EventType.STEGO_IMAGE :
                             isPdf   ? EventType.STEGO_PDF   :
                             null; // 확장자 외: 발행하지 않음

            if (type != null) {
                LogEvent ev = LogEvent.of(
                        type,
                        isImage ? "image" : "pdf",                 // source
                        abs,          // pageOrPath
                        null,                                       // browserTitle (알 수 없으면 null)
                        LogManager.getUserId()                      // 사용자ID (LogManager에 getter가 있어야 함)
                );
                LogEmitter.emit(ev, log);
            } else {
                // 혹시 모르는 확장자 — 단순 로그만
                LogManager.writeLog(log);
            }

            if (SecureAgentMain.TEST_MODE) {
                String decoded = SecureAgentMain.decodeOnce(file);
                System.out.println("[Stego] 디코딩 확인: " + decoded);
            }
        } else {
            LogManager.writeLog("[Stego] 삽입 실패 → " + file);
        }
    }

    /* ---------- helpers ---------- */

    private static void scheduleRetry(Path f) {
        retryExec.schedule(() -> process(f), 2, TimeUnit.SECONDS); // 최대 5회만
    }

    /** 다른 프로세스가 *exclusive write* 락을 쥐고 있으면 false */
    private static boolean isWritable(Path f) {
        try (FileChannel ch = FileChannel.open(f, WRITE)) {
            FileLock lock = ch.tryLock();
            if (lock != null) { lock.release(); return true; }
        } catch (Exception ignore) { }
        return false;
    }

    private static boolean isPdf(Path f) {
        return f.toString().toLowerCase().endsWith(".pdf");
    }
    private static boolean isImage(Path f) {
        String n = f.toString().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }
}
