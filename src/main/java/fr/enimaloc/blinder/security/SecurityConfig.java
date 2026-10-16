package fr.enimaloc.blinder.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebChains {

        @Bean
        @Order(1)
        SecurityFilterChain adminChain(HttpSecurity http, AdminRequestMatcher adminRequests) throws Exception {
            http.securityMatcher(adminRequests)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/_/login", "/_/setup", "/_/admin.css", "/error").permitAll()
                            .anyRequest().hasRole("ADMIN"))
                    .formLogin(form -> form
                            .loginPage("/_/login")
                            .defaultSuccessUrl("/_/", true)
                            .permitAll())
                    .logout(logout -> logout
                            .logoutUrl("/_/logout")
                            .logoutSuccessUrl("/_/login?logout"));
            return http.build();
        }

        @Bean
        @Order(2)
        SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .requestCache(cache -> cache.disable())
                    // Cache-Control est posé explicitement par RedirectController (no-store).
                    .headers(headers -> headers.cacheControl(cache -> cache.disable()))
                    // Aucun endpoint public ne modifie d'état au lot 1 ; le lot 2 réévaluera (formulaire mot de passe).
                    .csrf(csrf -> csrf.disable());
            return http.build();
        }
    }
}
