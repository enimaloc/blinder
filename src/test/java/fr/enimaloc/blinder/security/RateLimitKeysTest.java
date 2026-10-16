package fr.enimaloc.blinder.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeysTest {

    @Test
    void ipv4AddressReturnsUnchanged() {
        assertThat(RateLimitKeys.client("192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    void ipv4MappedIpv6ExtractsIpv4() {
        // IPv4-mapped IPv6: ::ffff:192.0.2.1
        assertThat(RateLimitKeys.client("::ffff:192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    void twoIpv6AddressesInSamePrefixReturnSameKey() {
        // Deux adresses dans le même /64: 2001:db8:1:2:a000:0000:0000:0001 et 2001:db8:1:2:b000:0000:0000:0001
        String addr1 = "2001:db8:1:2:a000::1";
        String addr2 = "2001:db8:1:2:b000::1";

        assertThat(RateLimitKeys.client(addr1)).isEqualTo(RateLimitKeys.client(addr2));
    }

    @Test
    void differentIpv6PrefixesReturnDifferentKeys() {
        String addr1 = "2001:db8:1:2::1";
        String addr2 = "2001:db8:1:3::1";

        assertThat(RateLimitKeys.client(addr1)).isNotEqualTo(RateLimitKeys.client(addr2));
    }

    @Test
    void garbageValueReturnsUnchanged() {
        assertThat(RateLimitKeys.client("not-an-ip")).isEqualTo("not-an-ip");
    }

    @Test
    void linkPasswordKey() {
        UUID linkId = UUID.randomUUID();
        String key = RateLimitKeys.linkPassword(linkId, "192.0.2.1");
        assertThat(key).startsWith("link-pw:" + linkId + ":");
    }

    @Test
    void linkPasswordAggregateKey() {
        UUID linkId = UUID.randomUUID();
        String key = RateLimitKeys.linkPasswordAggregate(linkId);
        assertThat(key).isEqualTo("link-pw:" + linkId);
    }

    @Test
    void adminLoginKey() {
        String key = RateLimitKeys.adminLogin("192.0.2.1");
        assertThat(key).startsWith("admin-login:");
    }

    @Test
    void ipv6WithEmbeddedNotationReturnsPrefix() {
        // 2001:db8:1:2::ffff:5 should return the /64 prefix, not IPv4
        String key = RateLimitKeys.client("2001:db8:1:2::ffff:5");
        assertThat(key).isEqualTo("2001:db8:1:2::/64");
    }

    @Test
    void ipv4MappedFullFormExtractsIpv4() {
        // 0:0:0:0:0:ffff:192.0.2.1 (full form)
        assertThat(RateLimitKeys.client("0:0:0:0:0:ffff:192.0.2.1")).isEqualTo("192.0.2.1");
    }

    @Test
    void ipv4MappedHexFormExtractsIpv4() {
        // ::ffff:c000:201 (hex form of 192.0.2.1)
        assertThat(RateLimitKeys.client("::ffff:c000:201")).isEqualTo("192.0.2.1");
    }

    @Test
    void twoIpv6AddressesInSamePrefixReturnSamePrefix() {
        String addr1 = "2001:0db8:0001:0002::1";
        String addr2 = "2001:db8:1:2::9";

        assertThat(RateLimitKeys.client(addr1)).isEqualTo(RateLimitKeys.client(addr2));
        assertThat(RateLimitKeys.client(addr1)).isEqualTo("2001:db8:1:2::/64");
    }

    @Test
    void differentIpv6PrefixesReturnDifferentPrefix() {
        String addr1 = "2001:db8:1:2::1";
        String addr2 = "2001:db8:1:3::1";

        String key1 = RateLimitKeys.client(addr1);
        String key2 = RateLimitKeys.client(addr2);

        assertThat(key1).isEqualTo("2001:db8:1:2::/64");
        assertThat(key2).isEqualTo("2001:db8:1:3::/64");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void garbageValueUnchanged() {
        assertThat(RateLimitKeys.client("garbage123")).isEqualTo("garbage123");
    }
}
