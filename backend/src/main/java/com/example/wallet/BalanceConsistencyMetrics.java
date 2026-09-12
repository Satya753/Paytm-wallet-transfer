package com.example.wallet;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asynchronous ledger invariant check. Credits are the only source of funds;
 * completed transfers must preserve the total balance across every wallet.
 */
@Component
public class BalanceConsistencyMetrics {
  private final JdbcTemplate jdbc;
  private final AtomicReference<BigDecimal> actual = new AtomicReference<>(BigDecimal.ZERO);
  private final AtomicReference<BigDecimal> expected = new AtomicReference<>(BigDecimal.ZERO);
  private final AtomicReference<BigDecimal> delta = new AtomicReference<>(BigDecimal.ZERO);
  private final AtomicInteger consistent = new AtomicInteger(1);

  public BalanceConsistencyMetrics(JdbcTemplate jdbc, MeterRegistry metrics) {
    this.jdbc = jdbc;
    register(metrics, "wallet.balance.total_paise", "Observed sum of all wallet balances", actual);
    register(metrics, "wallet.balance.expected_total_paise", "Sum of completed wallet credits", expected);
    register(metrics, "wallet.balance.delta_paise", "Observed balance minus expected balance", delta);
    Gauge.builder("wallet.balance.consistent", consistent, AtomicInteger::get)
        .description("1 when the wallet balance invariant holds, otherwise 0").register(metrics);
  }

  private void register(MeterRegistry metrics, String name, String description, AtomicReference<BigDecimal> value) {
    Gauge.builder(name, value, amount -> amount.get().doubleValue())
        .description(description).register(metrics);
  }

  @Scheduled(initialDelay = 5_000, fixedDelay = 10_000)
  public void publishConsistency() {
    Totals totals = jdbc.queryForObject("""
        SELECT
          COALESCE((SELECT SUM(balance_paise) FROM wallets), 0) AS actual_total,
          COALESCE((SELECT SUM(amount_paise) FROM wallet_credits WHERE status = 'COMPLETED'), 0) AS expected_total
        """, (rs, row) -> new Totals(rs.getBigDecimal("actual_total"), rs.getBigDecimal("expected_total")));
    actual.set(totals.actual());
    expected.set(totals.expected());
    BigDecimal difference = totals.actual().subtract(totals.expected());
    delta.set(difference);
    consistent.set(difference.signum() == 0 ? 1 : 0);
  }

  private record Totals(BigDecimal actual, BigDecimal expected) {}
}
