package fr.enimaloc.blinder.link.preview;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PreviewConfig {

    @Bean
    PreviewFetcher previewFetcher() {
        return new PreviewFetcher(new PublicAddressPolicy());
    }
}
