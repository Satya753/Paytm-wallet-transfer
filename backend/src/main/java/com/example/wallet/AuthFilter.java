package com.example.wallet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthFilter extends OncePerRequestFilter {
  private final Map<String, String> tokens;

  public AuthFilter(@Value("${wallet.tokens}") String tokenConfig) {
    tokens = Arrays.stream(tokenConfig.split(","))
        .map(String::trim).filter(s -> s.contains(":"))
        .map(s -> s.split(":", 2))
        .collect(Collectors.toUnmodifiableMap(a -> a[0], a -> a[1]));
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ") || !tokens.containsKey(header.substring(7))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return;
    }
    request.setAttribute("userId", tokens.get(header.substring(7)));
    chain.doFilter(request, response);
  }
}
