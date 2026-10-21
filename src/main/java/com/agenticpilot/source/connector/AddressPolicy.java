package com.agenticpilot.source.connector;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Decides which addresses the connector may connect to. Everything that is not a public internet address is refused:
 * loopback, private and link-local ranges (including the cloud metadata address), carrier-grade NAT, multicast, reserved
 * ranges and IPv6 unique-local addresses, also when an IPv4 address is embedded in an IPv6 one.
 */
public final class AddressPolicy {

    private AddressPolicy() {
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return publicV4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {                         // fc00::/7 unique local
                return false;
            }
            boolean mappedV4 = true;                              // ::a.b.c.d (deprecated IPv4-compatible)
            for (int i = 0; i < 12; i++) {
                mappedV4 &= b[i] == 0;
            }
            if (mappedV4) {
                return embedded(b[12], b[13], b[14], b[15]);
            }
            if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && b[3] == (byte) 0x9B) { // 64:ff9b::/96 NAT64
                return embedded(b[12], b[13], b[14], b[15]);
            }
            if (b[0] == 0x20 && b[1] == 0x02) {                   // 2002::/16 6to4 embeds an IPv4 address
                return embedded(b[2], b[3], b[4], b[5]);
            }
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) { // 2001::/32 Teredo
                return false;
            }
            if (b[0] == 0x01 && b[1] == 0x00 && b[2] == 0x00 && b[3] == 0x00 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0) { // 100::/64 discard
                return false;
            }
            return true;
        }
        return false;
    }

    /** An IPv4 address inside an IPv6 one gets the full IPv4 check. */
    private static boolean embedded(byte a, byte b, byte c, byte d) {
        try {
            return isPublic(InetAddress.getByAddress(new byte[] {a, b, c, d}));
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private static boolean publicV4(int a, int b, int c) {
        if (a == 0 || a >= 240) {                                  // "this" network, reserved, broadcast
            return false;
        }
        if (a == 100 && (b & 0xC0) == 64) {                        // 100.64.0.0/10 carrier-grade NAT
            return false;
        }
        if (a == 192 && b == 0 && c == 0) {                        // 192.0.0.0/24 protocol assignments
            return false;
        }
        if (a == 198 && (b == 18 || b == 19)) {                    // 198.18.0.0/15 benchmarking
            return false;
        }
        return !(a == 192 && b == 0 && c == 2) && !(a == 198 && b == 51 && c == 100) && !(a == 203 && b == 0 && c == 113); // documentation ranges
    }
}
