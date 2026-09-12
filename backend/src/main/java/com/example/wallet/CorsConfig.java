package com.example.wallet;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
  @Bean
  CorsFilter corsFilter(
      @Value("${app.cors.allowed-origin-patterns:http://localhost:*,https://*.onrender.com}")
      String allowedOriginPatterns) {
    CorsConfiguration config = new CorsConfiguration();
    List<String> patterns = Arrays.stream(allowedOriginPatterns.split(","))
        .map(String::trim)
        .filter(pattern -> !pattern.isEmpty())
        .toList();
    config.setAllowedOriginPatterns(patterns);
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
    config.setExposedHeaders(List.of("X-Correlation-Id"));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsFilter(source);
  }
}
