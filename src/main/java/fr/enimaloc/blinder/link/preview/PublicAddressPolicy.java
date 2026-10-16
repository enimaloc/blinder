package fr.enimaloc.blinder.link.preview;

import java.net.InetAddress;
import java.util.Arrays;

/** N'autorise que des adresses unicast publiques (anti-SSRF). */
public class PublicAddressPolicy implements AddressPolicy {

    @Override
    public boolean allowed(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return bytes.length == 4 ? allowedV4(bytes) : allowedV6(bytes);
    }

    static boolean allowedV4(byte[] b) {
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        int a2 = b[2] & 0xFF;
        if (a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224) {
            return false;
        }
        if (a0 == 100 && (a1 & 0xC0) == 64) {
            return false;
        }
        if (a0 == 169 && a1 == 254) {
            return false;
        }
        if (a0 == 172 && (a1 & 0xF0) == 16) {
            return false;
        }
        if (a0 == 192 && (a1 == 168 || (a1 == 0 && (a2 == 0 || a2 == 2)))) {
            return false;
        }
        if (a0 == 198 && ((a1 & 0xFE) == 18 || (a1 == 51 && a2 == 100))) {
            return false;
        }
        return !(a0 == 203 && a1 == 0 && a2 == 113);
    }

    static boolean allowedV6(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        if ((b0 & 0xFE) == 0xFC) {
            return false;
        }
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
            return false;
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return allowedV4(Arrays.copyOfRange(b, 2, 6));
        }
        boolean nat64 = b0 == 0x00 && b1 == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && allZero(b, 4, 12);
        if (nat64) {
            return allowedV4(Arrays.copyOfRange(b, 12, 16));
        }
        return !allZero(b, 0, 12);
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
