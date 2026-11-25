package io.github.joseferrao20.hookrelay.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <api-key>}. It never rejects by itself: a request
 * without a valid key stays anonymous and the entry point answers 401 where authentication is
 * required. Deliberately not a Spring bean, so it only runs inside the security filter chain.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

  private static final String PREFIX = "Bearer ";

  private final ApiKeyAuthenticator authenticator;

  ApiKeyAuthFilter(ApiKeyAuthenticator authenticator) {
    this.authenticator = authenticator;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null
        && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
        && authenticator.isValid(header.substring(PREFIX.length()).trim())) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  "api-key", null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
    }
    chain.doFilter(request, response);
  }
}
