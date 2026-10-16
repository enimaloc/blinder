package fr.enimaloc.blinder.link.preview;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class PublicAddressPolicyTest {

    final PublicAddressPolicy policy = new PublicAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
            "172.31.255.255", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "240.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fec0::1", "fc00::1", "fd12::1",
            "2001:db8::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "2002:a00:1::1",
            "::7f00:1", "::ffff:0:8.8.8.8", "64:ff9b:1::1", "2001::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2",
            "100::1", "2001:2::1", "2001:10::1", "3fff::1", "5f00::1"})
    void refusesNonPublicAddresses(String literal) throws Exception {
        assertThat(policy.allowed(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "93.184.215.14", "172.32.0.1", "2606:4700::1111",
            "64:ff9b::808:808", "2002:808:808::1"})
    void allowsPublicAddresses(String literal) throws Exception {
        assertThat(policy.allowed(InetAddress.getByName(literal))).as(literal).isTrue();
    }
}
