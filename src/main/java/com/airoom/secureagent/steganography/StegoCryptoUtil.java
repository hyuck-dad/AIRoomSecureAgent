package com.airoom.secureagent.steganography;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class StegoCryptoUtil {

    /*
디코딩 중 AES 복호화 단계에서 Base64 디코딩이 실패했어. 문제는 StegoCryptoUtil.decrypt() 안에서 받은 byte 배열이 Base64로 인코딩된 유효한 문자열이 아니었다는 것이야.
✅ 삽입 과정 (ImageStegoEncoder)
삽입할 데이터를 String encrypted = StegoCryptoUtil.encrypt(payload)로 암호화한 후
byte[] data = encrypted.getBytes(StandardCharsets.UTF_8);로 바이트 배열로 바꿈
문제는 암호화된 Base64 문자열이 UTF-8로 변환되면서 깨질 수 있음
⛔️ 왜 깨질까?
Base64 문자열은 다음과 같은 ASCII 문자 집합만 써야 정상인데:
하지만 UTF-8로 바이트 변환하면 1바이트씩 잘려서 일부 문자열이 손상될 수 있어.
✅ 해결 방법
String을 바이트로 바꿀 때는 Base64 인코딩된 문자열을 직접 .getBytes() 하면 안 되고, 반드시 .getEncoder().encode()를 써야 해!
🔧 ImageStegoEncoder 에서 아예 encrypt() 리턴값을 byte[]로 바로 받게 바꾸자
🔁 수정 방법 요약:
StegoCryptoUtil.encrypt() → byte[] 반환으로 바꾸기
ImageStegoEncoder.encode()에서는 암호화된 byte[]를 직접 LSB 삽입
ImageStegoDecoder.decode()에서는 byte[] → 그대로 복호화
    * */

    //Stego 삽입 전에 데이터를 AES-128 방식으로 암호화하고, Decoder에서는 다시 복호화할 수 있도록 도와주는 유틸리티 클래스

    // 16바이트 (128비트) 고정 키 - 실제 서비스에서는 외부 config로 분리 필요
    private static final String SECRET_KEY = "AIDT2025UserKey!"; // exactly 16 chars
    private static final String ALGORITHM = "AES";

    // 기존: Base64 문자열로 반환
    public static String encrypt(String plainText) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(), ALGORITHM);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(String cipherText) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(), ALGORITHM);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decodedBytes = Base64.getDecoder().decode(cipherText);
        byte[] decrypted = cipher.doFinal(decodedBytes);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    // ✅ Base64 없이 암호화된 바이트 직접 반환 (Stego 삽입용)
    public static byte[] encryptToBytes(String data) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(), ALGORITHM);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    // ✅ 암호화된 byte[] 직접 복호화 (Stego 추출용)
    public static String decryptFromBytes(byte[] encryptedBytes) throws Exception {
        SecretKeySpec key = new SecretKeySpec(SECRET_KEY.getBytes(), ALGORITHM);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(encryptedBytes); // ⛔ Base64 decode 하지 않음!
        return new String(decrypted, StandardCharsets.UTF_8);
    }

}