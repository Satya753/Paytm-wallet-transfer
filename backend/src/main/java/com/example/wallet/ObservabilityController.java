package com.example.wallet;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObservabilityController {
  private final PrometheusMeterRegistry metrics;
  private final DomainEventLog events;

  public ObservabilityController(PrometheusMeterRegistry metrics, DomainEventLog events) {
    this.metrics = metrics;
    this.events = events;
  }

  @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
  public String metrics() { return metrics.scrape(); }

  @GetMapping(value = "/logs", produces = MediaType.APPLICATION_JSON_VALUE)
  public List<Map<String, Object>> logs() { return events.recent(); }
}
