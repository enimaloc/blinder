package fr.enimaloc.blinder.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /**
     * Les webjars ne sont servis que sous {@code /_/vendor/} (donc derrière la connexion admin, voir
     * {@code spring.mvc.webjars-path-pattern}). Le conteneur servirait aussi {@code /webjars/**} depuis les
     * ressources des jars via le contexte servlet : ce gestionnaire sans emplacement répond 404 à la place.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/webjars/**");
    }
}
