package fr.enimaloc.blinder.security;

import fr.enimaloc.blinder.api.ApiProblems;
import fr.enimaloc.blinder.api.ApiRequestMatcher;
import fr.enimaloc.blinder.api.ApiTokenFilter;
import fr.enimaloc.blinder.api.ProblemWriter;
import fr.enimaloc.blinder.api.token.ApiTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebChains {

        /**
         * API REST : jeton {@code Bearer} uniquement. Sans état, sans CSRF ni formulaire ; le contexte de sécurité n'est
         * jamais lu dans (ni écrit en) session, donc la session admin n'ouvre pas l'API.
         */
        @Bean
        @Order(0)
        SecurityFilterChain apiChain(HttpSecurity http, ApiTokenService tokens, RateLimiters rateLimiters,
                                   ProblemWriter problems) throws Exception {
            SecurityContextRepository contexts = new RequestAttributeSecurityContextRepository();
            http.securityMatcher(new ApiRequestMatcher())
                    .addFilterBefore(new ApiTokenFilter(tokens, rateLimiters.apiAuth(),
                                    ApiProblems.unauthorized(problems), problems, contexts),
                            AnonymousAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .securityContext(context -> context.securityContextRepository(contexts))
                    .requestCache(cache -> cache.disable())
                    .formLogin(form -> form.disable())
                    .httpBasic(basic -> basic.disable())
                    .logout(logout -> logout.disable())
                    .exceptionHandling(errors -> errors
                            .authenticationEntryPoint(ApiProblems.unauthorized(problems))
                            .accessDeniedHandler(ApiProblems.forbidden(problems)));
            return http.build();
        }

        @Bean
        @Order(1)
        SecurityFilterChain adminChain(HttpSecurity http, AdminRequestMatcher adminRequests,
                                       RateLimiters rateLimiters) throws Exception {
            RateLimiter logins = rateLimiters.adminLogin();
            http.securityMatcher(adminRequests)
                    .addFilterBefore(new LoginRateLimitFilter(logins), UsernamePasswordAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/_/login", "/_/setup", "/_/admin.css", "/error",
                                    "/_/enroll/**", "/_/device/sync", "/_/device/resync").permitAll()
                            .anyRequest().hasRole("ADMIN"))
                    .formLogin(form -> form
                            .loginPage("/_/login")
                            .successHandler(loginSuccess(logins))
                            .failureHandler(new SimpleUrlAuthenticationFailureHandler("/_/login?error"))
                            .permitAll())
                    .logout(logout -> logout
                            .logoutUrl("/_/logout")
                            .logoutSuccessUrl("/_/login?logout"));
            return http.build();
        }

        /** Le succès remet le compteur à zéro ; l'échec n'enregistre rien (la tentative est déjà comptée par le filtre). */
        private static SimpleUrlAuthenticationSuccessHandler loginSuccess(RateLimiter logins) {
            SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler("/_/") {
                @Override
                public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                    Authentication authentication)
                        throws IOException, ServletException {
                    logins.reset(LoginRateLimitFilter.key(request));
                    super.onAuthenticationSuccess(request, response, authentication);
                }
            };
            handler.setAlwaysUseDefaultTargetUrl(true);
            return handler;
        }

        @Bean
        @Order(2)
        SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .requestCache(cache -> cache.disable())
                    // Cache-Control est posé explicitement par RedirectController (no-store).
                    .headers(headers -> headers
                            .cacheControl(cache -> cache.disable())
                            // Le mot de passe peut figurer dans l'URL (?pw=) : ne jamais le fuiter via Referer.
                            .referrerPolicy(policy -> policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                    // Formulaire de mot de passe : CSRF sans session, jeton dans un cookie HttpOnly
                    // (Thymeleaf l'injecte côté serveur via th:action).
                    .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()));
            return http.build();
        }
    }
}
