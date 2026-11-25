package io.github.joseferrao20.hookrelay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Stateless API-key authentication; everything except health, info and API docs needs a key. */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

  static final String[] PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    "/v3/api-docs",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html"
  };

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http, SecurityProperties props, ObjectMapper mapper) throws Exception {
    ApiKeyAuthFilter filter = new ApiKeyAuthFilter(new ApiKeyAuthenticator(props.apiKeys()));
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            a -> a.requestMatchers(PUBLIC_PATHS).permitAll().anyRequest().authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(new ProblemAuthenticationEntryPoint(mapper)))
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
