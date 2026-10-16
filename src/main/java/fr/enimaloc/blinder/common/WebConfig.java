package fr.enimaloc.blinder.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /** Bibliothèques front servies localement (sous {@code /_/}, donc derrière la connexion admin). */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/_/vendor/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }
}
