package fr.enimaloc.blinder.api;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver;

import java.util.EnumSet;
import java.util.List;

/** Câblage des erreurs de l'API hors contrôleurs : résolveur MVC et dispatch ERROR du conteneur. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiErrorConfig implements WebMvcConfigurer {

    private final ProblemWriter problems;

    public ApiErrorConfig(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        int index = 0;
        while (index < resolvers.size() && !(resolvers.get(index) instanceof ResponseStatusExceptionResolver)) {
            index++;
        }
        resolvers.add(index, new ApiExceptionResolver(problems));
    }

    @Bean
    FilterRegistrationBean<ApiErrorDispatchFilter> apiErrorDispatchFilter() {
        FilterRegistrationBean<ApiErrorDispatchFilter> registration =
                new FilterRegistrationBean<>(new ApiErrorDispatchFilter(problems));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.ERROR));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
