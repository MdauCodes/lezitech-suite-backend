package com.lezitech.suite.config;

import com.lezitech.suite.auth.JwtFilter;
import com.lezitech.suite.auth.JwtService;
import com.lezitech.suite.tenant.TenantTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, com.lezitech.suite.auth.TokenVersions versions,
                                    com.lezitech.suite.auth.SupportAccess support,
                                    com.lezitech.suite.property.portal.RenterSessions renters,
                                    @Value("${suite.cors-origins}") String origins) throws Exception {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        http.csrf(c -> c.disable())
                .cors(c -> c.configurationSource(source))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/public/**", "/api/auth/signup", "/api/auth/login", "/api/auth/code", "/api/auth/reset",
                                "/api/auth/staff-pin", "/api/auth/reset-pin",
                                "/api/platform/**", "/actuator/health", "/api/admin/auth/login", "/api/admin/auth/demo",
                                "/api/admin/auth/options").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("PLATFORM")
                        // Workspace endpoints need a workspace login; a control-panel token is refused here.
                        .anyRequest().access((auth, ctx) -> new org.springframework.security.authorization.AuthorizationDecision(
                                auth.get() != null && auth.get().isAuthenticated()
                                        && !(auth.get() instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                                        && auth.get().getAuthorities().stream().noneMatch(g -> "ROLE_PLATFORM".equals(g.getAuthority())))))
                .addFilterBefore(new JwtFilter(jwt, versions, support, renters), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantTransactionManager(dataSource);
    }
}
