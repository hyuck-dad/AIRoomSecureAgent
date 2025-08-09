package com.airoom.secureagent.device;

import java.net.NetworkInterface;
import java.util.*;
// MAC 수집(+품질 판단)
public class MacAddressCollector {

    public static class Result {
        public final String primaryMac;      // 후보 중 최우선
        public final List<String> allMacs;   // 정규화된 전체 목록
        public final MacQuality quality;
        public final boolean vmSuspect;

        public Result(String primaryMac, List<String> allMacs, MacQuality quality, boolean vmSuspect) {
            this.primaryMac = primaryMac;
            this.allMacs = allMacs;
            this.quality = quality;
            this.vmSuspect = vmSuspect;
        }
    }

    public static Result collect() {
        List<String> macs = new ArrayList<>();
        boolean anyRandom = false;
        boolean anyVm = false;

        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) return new Result(null, List.of(), MacQuality.NONE, false);

            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                try {
                    if (!nic.isUp() || nic.isLoopback()) continue;

                    byte[] hw = nic.getHardwareAddress();
                    if (hw == null || hw.length < 6) continue;

                    String mac = toHexMac(hw);
                    macs.add(mac);

                    // Locally Administered bit 체크 (랜덤/스푸핑 흔적)
                    boolean locallyAdministered = (hw[0] & 0x02) != 0;
                    if (locallyAdministered) anyRandom = true;

                    // 가상 NIC 힌트 (이름 기반 휴리스틱)
                    String name = (nic.getName() + " " + nic.getDisplayName()).toLowerCase();
                    if (name.contains("vmware") || name.contains("virtualbox") ||
                            name.contains("hyper-v") || name.contains("veth") ||
                            name.contains("vnic") || name.contains("tap") ||
                            name.contains("vpn") || name.contains("nat")) {
                        anyVm = true;
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}

        // primary MAC 선택 로직: GOOD > RANDOM > NONE
        String primary = null;
        MacQuality quality = MacQuality.NONE;
        if (!macs.isEmpty()) {
            // GOOD을 우선 선택
            Optional<String> good = macs.stream().filter(m -> !isLocallyAdministered(m)).findFirst();
            if (good.isPresent()) {
                primary = good.get();
                quality = anyVm ? MacQuality.VM : MacQuality.GOOD;
            } else {
                // 전부 랜덤이면 그 중 첫 번째
                primary = macs.get(0);
                quality = MacQuality.RANDOM;
            }
        }

        return new Result(primary, List.copyOf(macs), quality, anyVm);
    }

    private static String toHexMac(byte[] hw) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < hw.length; i++) {
            sb.append(String.format("%02X", hw[i]));
            if (i < hw.length - 1) sb.append(":");
        }
        return sb.toString();
    }

    private static boolean isLocallyAdministered(String mac) {
        if (mac == null || mac.length() < 2) return false;
        int firstByte = Integer.parseInt(mac.substring(0, 2), 16);
        return (firstByte & 0x02) != 0;
    }
}
