package com.example.wallet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String correlationId = request.getHeader("X-Correlation-Id");
    if (correlationId == null || correlationId.isBlank() || correlationId.length() > 128)
      correlationId = UUID.randomUUID().toString();
    MDC.put("correlation_id", correlationId);
    response.setHeader("X-Correlation-Id", correlationId);
    try { chain.doFilter(request, response); }
    finally { MDC.remove("correlation_id"); }
  }
}
