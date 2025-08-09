package com.airoom.secureagent.device;
// MAC 품질 태그 enum
public enum MacQuality {
    NONE,        // 수집 실패
    RANDOM,      // Locally Administered (랜덤/스푸핑 의심)
    VM,          // 가상 NIC 의심
    GOOD         // 물리 NIC로 보이며 랜덤 비트 아님
}
/*
MAC Quality=VM, VM Suspect=YES 의미
**“이 PC가 무조건 가상 머신”**이라는 뜻은 아님.
내 휴리스틱은 가상 NIC 흔적(예: Hyper-V, VirtualBox, VPN 등 어댑터 이름) 이 보이거나 LAA(Locally Administered) 비트가 서 있으면 VM/YES로 태그해.
네 출력에서 00:15:5D는 Hyper-V 계열 OUI로 유명하고, 0A:…는 LAA 비트가 켜진 값이라 가상/가공 NIC 존재로 판단된 거야. 물리 머신이라도 Hyper-V, WSL2, Docker, VPN 드라이버가 있으면 이렇게 나올 수 있어.
*/
