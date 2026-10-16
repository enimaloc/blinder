package fr.enimaloc.blinder.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpAllowlistTest {

    @Test
    void normalizesLinesCommasAndSpacesAndDeduplicates() {
        assertThat(IpAllowlist.normalize("10.0.0.0/8\n203.0.113.9, 203.0.113.9  2001:db8::/32"))
                .isEqualTo("10.0.0.0/8,203.0.113.9,2001:db8:0:0:0:0:0:0/32");
    }

    @Test
    void blankInputIsNull() {
        assertThat(IpAllowlist.normalize(null)).isNull();
        assertThat(IpAllowlist.normalize("  \n ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "localhost", "10.0.0.256", "10.0.0.0/33", "2001:db8::/129",
            "10.0.0.0/-1", "10.0.0.0/", "1:2", "fe80::1%eth0", "10.0.0", "::g",
            "a:b", ":::", "1.2.3.4:5", "::1.2.3.256", "dead:beef", "010.0.0.1", "1.2.3.4::", "1.2.3.4::5"})
    void rejectsInvalidEntriesWithoutDns(String entry) {
        assertThatThrownBy(() -> IpAllowlist.normalize(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(entry);
    }

    @Test
    void rejectsMoreThanFiftyEntries() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            input.append("10.0.0.").append(i).append('\n');
        }
        assertThatThrownBy(() -> IpAllowlist.normalize(input.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
    }

    @Test
    void matchesIpv4ExactAndCidr() {
        IpAllowlist list = IpAllowlist.parse("203.0.113.9,10.0.0.0/8");
        assertThat(list.matches("203.0.113.9")).isTrue();
        assertThat(list.matches("203.0.113.10")).isFalse();
        assertThat(list.matches("10.255.1.2")).isTrue();
        assertThat(list.matches("11.0.0.1")).isFalse();
    }

    @Test
    void matchesPartialByteMask() {
        IpAllowlist list = IpAllowlist.parse("192.168.1.128/25");
        assertThat(list.matches("192.168.1.200")).isTrue();
        assertThat(list.matches("192.168.1.127")).isFalse();
    }

    @Test
    void matchesIpv6AndIpv4Mapped() {
        IpAllowlist list = IpAllowlist.parse("2001:db8:0:0:0:0:0:0/32,203.0.113.9");
        assertThat(list.matches("2001:db8::1")).isTrue();
        assertThat(list.matches("2001:db9::1")).isFalse();
        assertThat(list.matches("::ffff:203.0.113.9")).isTrue();
        assertThat(list.matches("0:0:0:0:0:0:0:1")).isFalse();
    }

    @Test
    void zeroPrefixMatchesWholeFamilyOnly() {
        IpAllowlist list = IpAllowlist.parse("0.0.0.0/0");
        assertThat(list.matches("8.8.8.8")).isTrue();
        assertThat(list.matches("2001:db8::1")).isFalse();
    }

    @Test
    void ipv6ZeroPrefixMatchesOnlyIpv6() {
        IpAllowlist list = IpAllowlist.parse("::/0");
        assertThat(list.matches("2001:db8::1")).isTrue();
        assertThat(list.matches("0:0:0:0:0:0:0:0")).isTrue();
        assertThat(list.matches("203.0.113.9")).isFalse(); // IPv4 does not match IPv6 prefix
    }

    @Test
    void emptyAndGarbageNeverMatch() {
        assertThat(IpAllowlist.parse(null).isEmpty()).isTrue();
        assertThat(IpAllowlist.parse("").matches("10.0.0.1")).isFalse();
        assertThat(IpAllowlist.parse("not-an-ip").isEmpty()).isTrue();
        assertThat(IpAllowlist.parse("10.0.0.0/8").matches("garbage")).isFalse();
        assertThat(IpAllowlist.parse("10.0.0.0/8").matches(null)).isFalse();
    }
}
