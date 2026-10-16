package fr.enimaloc.blinder.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.LiteWebJarsResourceResolver;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.webjars.WebJarVersionLocator;

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
        // Chart.js sans numéro de version dans l'URL. Déclaré ici car la chaîne de ressources de Boot est
        // désactivée (spring.web.resources.chain.enabled, voir application.properties).
        registry.addResourceHandler("/_/vendor/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/")
                .resourceChain(true)
                .addResolver(new LiteWebJarsResourceResolver(new WebJarVersionLocator()))
                .addResolver(new PathResourceResolver());
    }
}
