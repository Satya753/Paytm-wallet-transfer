package com.example.wallet;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import net.logstash.logback.argument.StructuredArguments;

/** Recent non-sensitive domain events, retained for the public development observability feed. */
@Component
public class DomainEventLog {
  private static final int MAX_EVENTS = 500;
  private static final Logger LOG = LoggerFactory.getLogger("domain.events");
  private final ConcurrentLinkedDeque<Map<String, Object>> events = new ConcurrentLinkedDeque<>();

  public void record(String event, Map<String, ?> fields) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("timestamp", OffsetDateTime.now().toString());
    entry.put("correlation_id", java.util.Objects.requireNonNullElse(MDC.get("correlation_id"), "system"));
    entry.put("event", event);
    entry.putAll(fields);
    Map<String, Object> immutableEntry = Map.copyOf(entry);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { publish(immutableEntry); }
      });
    } else publish(immutableEntry);
  }

  private void publish(Map<String, Object> entry) {
    events.addFirst(entry);
    while (events.size() > MAX_EVENTS) events.pollLast();
    LOG.info("domain_event", StructuredArguments.entries(entry));
  }

  public List<Map<String, Object>> recent() { return new ArrayList<>(events); }
}
