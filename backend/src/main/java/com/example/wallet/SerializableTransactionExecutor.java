package com.example.wallet;

import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs a small write unit at PostgreSQL SERIALIZABLE isolation and retries transient conflicts. */
@Component
public class SerializableTransactionExecutor {
  private static final int MAX_ATTEMPTS = 4;
  private final TransactionTemplate transaction;

  public SerializableTransactionExecutor(PlatformTransactionManager transactionManager) {
    transaction = new TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setTimeout(5);
  }

  public <T> T execute(Supplier<T> work) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return transaction.execute(status -> work.get());
      } catch (ConcurrencyFailureException exception) {
        if (attempt == MAX_ATTEMPTS)
          throw new ApiException(503, "wallet is busy; retry the request with the same idempotency_key");
        // A short increasing pause avoids immediately colliding with the same transaction again.
        try { Thread.sleep(10L * attempt); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw exception; }
      }
    }
    throw new IllegalStateException("unreachable");
  }
}
