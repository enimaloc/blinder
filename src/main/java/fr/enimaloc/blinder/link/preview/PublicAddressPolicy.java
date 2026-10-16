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
        int b2 = b[2] & 0xFF;
        int b3 = b[3] & 0xFF;
        // NAT64 64:ff9b::/96 : on contrôle l'IPv4 embarquée ; 64:ff9b:1::/48 (usage local) est refusé
        if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B && allZero(b, 4, 12)) {
            return allowedV4(Arrays.copyOfRange(b, 12, 16));
        }
        // seul l'unicast global 2000::/3 est autorisé
        if ((b0 & 0xE0) != 0x20) {
            return false;
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return allowedV4(Arrays.copyOfRange(b, 2, 6));
        }
        if (b0 == 0x20 && b1 == 0x01) {
            if (b2 == 0x00 && b3 == 0x00) {
                return false; // Teredo 2001::/32
            }
            if (b2 == 0x00 && b3 == 0x02 && (b[4] & 0xFF) == 0 && (b[5] & 0xFF) == 0) {
                return false; // 2001:2::/48
            }
            if (b2 == 0x00 && (b3 & 0xF0) == 0x10) {
                return false; // 2001:10::/28
            }
            if (b2 == 0x0D && b3 == 0xB8) {
                return false; // documentation 2001:db8::/32
            }
        }
        // 3fff::/20 (documentation)
        return !(b0 == 0x3F && b1 == 0xFF && (b2 & 0xF0) == 0);
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
