package fr.enimaloc.blinder.setup;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Tant que le setup n'est pas fait, toute page admin renvoie vers /_/setup. */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class SetupInterceptor implements WebMvcConfigurer, HandlerInterceptor {

    private final SetupGuard guard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns("/_/**")
                .excludePathPatterns("/_/setup", "/_/admin.css");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (guard.isSetupPending()) {
            response.sendRedirect(request.getContextPath() + "/_/setup");
            return false;
        }
        return true;
    }
}
