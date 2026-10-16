package fr.enimaloc.blinder.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PasswordEncoderWiringTest {

    @Autowired
    PasswordEncoder encoder;

    @Test
    void passwordEncoderAvailableWithoutWebEnvironment() {
        assertThat(encoder.matches("secret", encoder.encode("secret"))).isTrue();
    }
}
