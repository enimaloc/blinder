package fr.enimaloc.blinder.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IpLiteralsTest {

    @Test
    void parsesIpv4() {
        var result = IpLiterals.parse("192.0.2.1");
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(4);
        assertThat(result.get()[0] & 0xFF).isEqualTo(192);
        assertThat(result.get()[1] & 0xFF).isEqualTo(0);
        assertThat(result.get()[2] & 0xFF).isEqualTo(2);
        assertThat(result.get()[3] & 0xFF).isEqualTo(1);
    }

    @Test
    void rejectsIpv4WithLeadingZeros() {
        assertThat(IpLiterals.parse("010.0.0.1")).isEmpty();
    }

    @Test
    void rejectsIpv4WithInvalidOctet() {
        assertThat(IpLiterals.parse("256.0.0.1")).isEmpty();
    }

    @Test
    void rejectsIpv4WithTooFewOctets() {
        assertThat(IpLiterals.parse("192.0.2")).isEmpty();
    }

    @Test
    void parsesIpv6() {
        var result = IpLiterals.parse("2001:db8:0:0:0:0:0:1");
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(16);
    }

    @Test
    void parsesIpv6WithCompression() {
        var result = IpLiterals.parse("2001:db8::1");
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(16);
    }

    @Test
    void parsesIpv4MappedIpv6() {
        var result = IpLiterals.parse("::ffff:192.0.2.1");
        assertThat(result).isPresent();
        byte[] bytes = result.get();
        assertThat(bytes).hasSize(4); // IPv4-mapped returns 4 bytes
        assertThat(bytes[0] & 0xFF).isEqualTo(192);
        assertThat(bytes[1] & 0xFF).isEqualTo(0);
        assertThat(bytes[2] & 0xFF).isEqualTo(2);
        assertThat(bytes[3] & 0xFF).isEqualTo(1);
    }

    @Test
    void parsesIpv4MappedIpv6FullForm() {
        var result = IpLiterals.parse("0:0:0:0:0:ffff:192.0.2.1");
        assertThat(result).isPresent();
        byte[] bytes = result.get();
        assertThat(bytes).hasSize(4); // IPv4-mapped returns 4 bytes
        assertThat(bytes[0] & 0xFF).isEqualTo(192);
    }

    @Test
    void parsesIpv6HexMapped() {
        var result = IpLiterals.parse("::ffff:c000:201");
        assertThat(result).isPresent();
        byte[] bytes = result.get();
        assertThat(bytes).hasSize(4); // IPv4-mapped returns 4 bytes
        assertThat(bytes[0] & 0xFF).isEqualTo(192);
        assertThat(bytes[1] & 0xFF).isEqualTo(0);
        assertThat(bytes[2] & 0xFF).isEqualTo(2);
        assertThat(bytes[3] & 0xFF).isEqualTo(1);
    }

    @Test
    void rejectsIpv6WithDoubleColonDoubleColon() {
        assertThat(IpLiterals.parse("2001:db8:::1")).isEmpty();
    }

    @Test
    void rejectsIpv6WithInvalidCharacters() {
        assertThat(IpLiterals.parse("2001:db8:x::1")).isEmpty();
    }

    @Test
    void rejectsPortNotation() {
        assertThat(IpLiterals.parse("1.2.3.4:5")).isEmpty();
    }

    @Test
    void rejectsInvalidPattern() {
        assertThat(IpLiterals.parse("dead:beef")).isEmpty();
    }

    @Test
    void rejectsTooShortIpv6() {
        assertThat(IpLiterals.parse("a:b")).isEmpty();
    }

    @Test
    void rejectsIpv6WithInvalidIpv4Suffix() {
        assertThat(IpLiterals.parse("::1.2.3.256")).isEmpty();
    }

    @Test
    void rejectsZoneId() {
        assertThat(IpLiterals.parse("fe80::1%eth0")).isEmpty();
    }

    @Test
    void rejectsBrackets() {
        assertThat(IpLiterals.parse("[::1]")).isEmpty();
    }

    @Test
    void rejectsPrefix() {
        assertThat(IpLiterals.parse("2001:db8::1/64")).isEmpty();
    }

    @Test
    void formatsIpv4() {
        byte[] bytes = {(byte) 192, 0, 2, 1};
        assertThat(IpLiterals.format(bytes)).isEqualTo("192.0.2.1");
    }

    @Test
    void formatsIpv6() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0x20;
        bytes[1] = 1;
        bytes[2] = 0x0d;
        bytes[3] = (byte) 0xb8;
        // Rest is zero
        String formatted = IpLiterals.format(bytes);
        assertThat(formatted).isEqualTo("2001:db8:0:0:0:0:0:0");
    }

    @Test
    void rejectsEmpty() {
        assertThat(IpLiterals.parse("")).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(IpLiterals.parse(null)).isEmpty();
    }

    @Test
    void rejectsIpv4BeforeCompression() {
        assertThat(IpLiterals.parse("1.2.3.4::5")).isEmpty();
    }

    @Test
    void rejectsIpv4InNonLastPosition() {
        assertThat(IpLiterals.parse("1.2.3.4::")).isEmpty();
    }

    @Test
    void acceptsDoubleColonAlone() {
        var result = IpLiterals.parse("::");
        assertThat(result).isPresent();
        byte[] bytes = result.get();
        assertThat(bytes.length).isEqualTo(16);
        // All bytes should be zero
        for (byte b : bytes) {
            assertThat(b).isEqualTo((byte) 0);
        }
    }

    @Test
    void rejects5HexDigitHextet() {
        assertThat(IpLiterals.parse("12345::1")).isEmpty();
    }

    @Test
    void rejectsNineGroups() {
        assertThat(IpLiterals.parse("1:2:3:4:5:6:7:8:9")).isEmpty();
    }

    @Test
    void rejectsSevenGroupsWithoutCompression() {
        assertThat(IpLiterals.parse("1:2:3:4:5:6:7")).isEmpty();
    }

    @Test
    void nonMappedIpv6WithFffeMask() {
        // 0:0:0:0:0:fffe:c000:201 is NOT IPv4-mapped (fffe, not ffff)
        var result = IpLiterals.parse("0:0:0:0:0:fffe:c000:201");
        assertThat(result).isPresent();
        assertThat(result.get().length).isEqualTo(16); // Returns 16 bytes, not 4
    }

    @Test
    void ipv6WithEmbeddedHexNotation() {
        // 2001:db8:1:2::ffff:5 has ffff in a non-mapped position (not ::ffff:), returns 16 bytes
        var result = IpLiterals.parse("2001:db8:1:2::ffff:5");
        assertThat(result).isPresent();
        assertThat(result.get().length).isEqualTo(16); // Returns 16 bytes, not 4
    }

    @ParameterizedTest
    @ValueSource(strings = {"a:b", ":::", "1.2.3.4:5", "::1.2.3.256", "dead:beef",
            "010.0.0.1", "1.2.3.4::", "1.2.3.4::5", "1.2.3.4:1::", "\u0661.2.3.4", "12345::1",
            "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7", "fe80::1%eth0", "[::1]", "::/0", "1::2::3", "1:2:3:4:5:6:7:8::"})
    void rejectsMalformed(String literal) {
        assertThat(IpLiterals.parse(literal)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"::ffff:192.0.2.1", "0:0:0:0:0:ffff:192.0.2.1", "::ffff:c000:201"})
    void ipv4MappedYieldsFourBytes(String literal) {
        assertThat(IpLiterals.parse(literal).get()).isEqualTo(new byte[] {(byte) 192, 0, 2, 1});
    }

    @Test
    void nonMappedEmbeddedIpv4StaysSixteenBytes() {
        assertThat(IpLiterals.parse("64:ff9b::192.0.2.1").get()).hasSize(16);
    }

    @Test
    void formatsIpv6Uncompressed() {
        assertThat(IpLiterals.format(IpLiterals.parse("2001:db8::1").get())).isEqualTo("2001:db8:0:0:0:0:0:1");
    }
}
