package com.airoom.secureagent.device;

import java.util.Optional;
// 오케스트레이터(수집→정규화→로그 출력)
// 콘솔 검증은 SecureAgentMain에서 DeviceFingerprintCollector.collect()를 호출해
// 수집된 값/품질 태그/복합 Device ID를 출력하도록 연결.
public class DeviceFingerprintCollector {

    public static DeviceFingerprint collect() {
        var mac = MacAddressCollector.collect();

        String machineGuid = WindowsSystemIdReader.readMachineGuid().orElse(null);
        String bios = WindowsSystemIdReader.readBiosSerial().orElse(null);
        String board = WindowsSystemIdReader.readBaseboardSerial().orElse(null);
        String disk = WindowsSystemIdReader.readDiskSerial().orElse(null);
        String cpu  = WindowsSystemIdReader.readCpuId().orElse(null);

        return new DeviceFingerprint(
                machineGuid,
                mac.primaryMac,
                mac.allMacs,
                bios,
                board,
                disk,
                cpu,
                mac.quality,
                mac.vmSuspect
        );
    }

    public static void print(DeviceFingerprint fp) {
        System.out.println("=== Device Fingerprint ===");
        System.out.println("MachineGuid   : " + nv(fp.machineGuid()));
        System.out.println("Primary MAC   : " + nv(fp.primaryMac()));
        System.out.println("All MACs      : " + (fp.macList() == null ? "-" : fp.macList()));
        System.out.println("BIOS Serial   : " + nv(fp.biosSerial()));
        System.out.println("Board Serial  : " + nv(fp.baseboardSerial()));
        System.out.println("Disk Serial   : " + nv(fp.diskSerial()));
        System.out.println("CPU ID        : " + nv(fp.cpuId()));
        System.out.println("MAC Quality   : " + (fp.macQuality() == null ? "NONE" : fp.macQuality()));
        System.out.println("VM Suspect    : " + (fp.vmSuspect() ? "YES" : "NO"));

        String deviceId = DeviceIdGenerator.compute(fp);
        System.out.println("Composite Device ID : " + deviceId);
        System.out.println("=========================");
    }

    private static String nv(String s) { return (s == null || s.isBlank()) ? "-" : s; }
}
