package org.volunteers.scheduler.config;

import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
class SecurityConfig {
    @Bean
    @Order(0)
    @ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
    SecurityFilterChain h2ConsoleSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(PathRequest.toH2Console())
                .authorizeHttpRequests(r -> r.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .build();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(r -> r.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable).httpBasic(b -> b.disable()).build();
    }

    @Bean
    UserDetailsService users() { return new InMemoryUserDetailsManager(); }
}
