package fr.enimaloc.blinder.link.check;

import fr.enimaloc.blinder.link.preview.PublicAddressPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HeaderCheckConfig {

    @Bean
    HeaderCheckClient headerCheckClient() {
        return new HeaderCheckClient(new PublicAddressPolicy());
    }
}
