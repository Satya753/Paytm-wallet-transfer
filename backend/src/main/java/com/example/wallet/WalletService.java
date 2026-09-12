package com.example.wallet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WalletService {
  private final JdbcTemplate jdbc;
  private final SerializableTransactionExecutor serializable;
  private final DomainEventLog events;
  private final Counter transfersCreated;
  private final Counter transfersDeclinedInsufficientFunds;
  private final Counter idempotentReplays;
  private final RowMapper<Wallet> walletMapper = (rs, n) -> wallet(rs);
  private final RowMapper<Transfer> transferMapper = (rs, n) -> transfer(rs);
  private final RowMapper<WalletTransaction> historyMapper = (rs, n) -> history(rs);

  public WalletService(JdbcTemplate jdbc, SerializableTransactionExecutor serializable, DomainEventLog events, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.serializable = serializable;
    this.events = events;
    this.transfersCreated = Counter.builder("wallet.transfers.created").description("Transfers first accepted for processing").register(metrics);
    this.transfersDeclinedInsufficientFunds = Counter.builder("wallet.transfers.declined.insufficient_funds").description("Transfers declined for insufficient funds").register(metrics);
    this.idempotentReplays = Counter.builder("wallet.idempotent.replays").description("Idempotency replays served from a prior result").register(metrics);
  }

  public record Wallet(UUID id, String userId, String upiId, long balancePaise) {}
  public record Transfer(UUID id, UUID from, UUID to, long amountPaise, String status, OffsetDateTime createdAt) {}
  public record Credit(UUID id, UUID walletId, long amountPaise, String status, OffsetDateTime createdAt) {}
  public record WalletTransaction(UUID id, String type, String direction, long amountPaise,
                                  String status, String counterpartyUpiId, OffsetDateTime createdAt) {}

  @Transactional
  public Wallet getOrCreateWallet(String userId) {
    UUID id = UUID.randomUUID();
    return jdbc.queryForObject("""
        INSERT INTO wallets (id, user_id, upi_id) VALUES (?, ?, ?)
        ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id
        RETURNING id, user_id, upi_id, balance_paise
        """, walletMapper, id, userId, userId + "@wallet");
  }

  public Wallet getWallet(String upiId, String caller) {
    Wallet wallet = walletByUpiId(upiId);
    requireOwner(wallet, caller);
    return wallet;
  }

  public Transfer transfer(String fromUpiId, String toUpiId, long amount, String key, String caller) {
    return serializable.execute(() -> transferInTransaction(fromUpiId, toUpiId, amount, key, caller));
  }

  private Transfer transferInTransaction(String fromUpiId, String toUpiId, long amount, String key, String caller) {
    if (amount <= 0) throw new ApiException(400, "amount_paise must be a positive integer");
    if (fromUpiId.equalsIgnoreCase(toUpiId)) throw new ApiException(400, "from and to must differ");
    if (key == null || key.isBlank() || key.length() > 255) throw new ApiException(400, "invalid idempotency_key");

    // Validate authorization before reserving a globally unique idempotency key.
    Wallet requestedSource = walletByUpiId(fromUpiId);
    Wallet requestedDestination = walletByUpiId(toUpiId);
    UUID from = requestedSource.id();
    UUID to = requestedDestination.id();
    requireOwner(requestedSource, caller);
    UUID id = UUID.randomUUID();
    var inserted = jdbc.queryForList("""
            INSERT INTO transfers (id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """, UUID.class, id, key, from, to, amount);
    if (inserted.isEmpty()) {
      Transfer existing = findByKey(key);
      requireOwner(walletById(existing.from()), caller);
      if (!existing.from().equals(from) || !existing.to().equals(to) || existing.amountPaise() != amount)
        throw new ApiException(409, "idempotency_key was already used with a different request");
      countAfterCommit(idempotentReplays);
      events.record("idempotent_replay_hit", Map.of("transfer_id", existing.id().toString(), "from", fromUpiId, "to", toUpiId));
      return existing;
    }
    countAfterCommit(transfersCreated);
    events.record("transfer_created", Map.of("transfer_id", id.toString(), "from", requestedSource.upiId(), "to", requestedDestination.upiId(), "amount_paise", amount));

    // Conditional debit is atomic. PostgreSQL locks only the source row being changed,
    // rather than holding explicit locks on both wallets before performing the update.
    int debited = jdbc.update("UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND balance_paise >= ?", amount, from, amount);
    if (debited == 0) {
      jdbc.update("UPDATE transfers SET status = 'REJECTED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
      countAfterCommit(transfersDeclinedInsufficientFunds);
      events.record("transfer_declined_insufficient_funds", Map.of("transfer_id", id.toString(), "from", requestedSource.upiId(), "amount_paise", amount));
      return findById(id);
    }
    events.record("transfer_debited", Map.of("transfer_id", id.toString(), "from", requestedSource.upiId(), "amount_paise", amount));
    jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?", amount, to);
    events.record("transfer_credited", Map.of("transfer_id", id.toString(), "to", requestedDestination.upiId(), "amount_paise", amount));
    jdbc.update("UPDATE transfers SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    return findById(id);
  }

  public Transfer getTransfer(UUID id, String caller) {
    Transfer transfer = findById(id);
    requireOwner(walletById(transfer.from()), caller);
    return transfer;
  }

  public String upiId(UUID walletId) { return walletById(walletId).upiId(); }

  /** Adds funds only to a wallet owned by the authenticated caller. */
  public Credit credit(String upiId, long amount, String key, String caller) {
    return serializable.execute(() -> creditInTransaction(upiId, amount, key, caller));
  }

  private Credit creditInTransaction(String upiId, long amount, String key, String caller) {
    if (amount <= 0) throw new ApiException(400, "amount_paise must be a positive integer");
    if (key == null || key.isBlank() || key.length() > 255) throw new ApiException(400, "invalid idempotency_key");
    Wallet requestedWallet = walletByUpiId(upiId);
    UUID walletId = requestedWallet.id();
    requireOwner(requestedWallet, caller);

    UUID id = UUID.randomUUID();
    var inserted = jdbc.queryForList("""
        INSERT INTO wallet_credits (id, wallet_id, amount_paise, idempotency_key, status)
        VALUES (?, ?, ?, ?, 'PENDING')
        ON CONFLICT (idempotency_key) DO NOTHING
        RETURNING id
        """, UUID.class, id, walletId, amount, key);
    if (inserted.isEmpty()) {
      Credit existing = findCreditByKey(key);
      requireOwner(walletById(existing.walletId()), caller);
      if (!existing.walletId().equals(walletId) || existing.amountPaise() != amount)
        throw new ApiException(409, "idempotency_key was already used with a different request");
      countAfterCommit(idempotentReplays);
      events.record("idempotent_replay_hit", Map.of("credit_id", existing.id().toString(), "wallet", requestedWallet.upiId()));
      return existing;
    }
    events.record("balance_credit_created", Map.of("credit_id", id.toString(), "wallet", requestedWallet.upiId(), "amount_paise", amount));
    jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?", amount, walletId);
    jdbc.update("UPDATE wallet_credits SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    events.record("balance_credited", Map.of("credit_id", id.toString(), "wallet", requestedWallet.upiId(), "amount_paise", amount));
    return findCreditById(id);
  }

  public java.util.List<WalletTransaction> history(String upiId, String caller) {
    Wallet requestedWallet = walletByUpiId(upiId);
    UUID walletId = requestedWallet.id();
    requireOwner(requestedWallet, caller);
    return jdbc.query("""
        SELECT entries.id, type, direction, amount_paise, status, wallets.upi_id AS counterparty_upi_id, created_at FROM (
          SELECT id, 'CREDIT' AS type, 'CREDIT' AS direction, amount_paise, status,
                 CAST(NULL AS UUID) AS counterparty_wallet_id, created_at
          FROM wallet_credits WHERE wallet_id = ?
          UNION ALL
          SELECT id, 'TRANSFER' AS type, 'DEBIT' AS direction, amount_paise, status,
                 to_wallet_id AS counterparty_wallet_id, created_at
          FROM transfers WHERE from_wallet_id = ?
          UNION ALL
          SELECT id, 'TRANSFER' AS type, 'CREDIT' AS direction, amount_paise, status,
                 from_wallet_id AS counterparty_wallet_id, created_at
          FROM transfers WHERE to_wallet_id = ?
        ) entries LEFT JOIN wallets ON wallets.id = entries.counterparty_wallet_id ORDER BY created_at DESC
        """, historyMapper, walletId, walletId, walletId);
  }

  private Wallet walletById(UUID id) {
    try { return jdbc.queryForObject("SELECT id, user_id, upi_id, balance_paise FROM wallets WHERE id = ?", walletMapper, id); }
    catch (EmptyResultDataAccessException e) { throw new ApiException(404, "wallet not found"); }
  }
  private Wallet walletByUpiId(String upiId) {
    try { return jdbc.queryForObject("SELECT id, user_id, upi_id, balance_paise FROM wallets WHERE lower(upi_id) = lower(?)", walletMapper, upiId); }
    catch (EmptyResultDataAccessException e) { throw new ApiException(404, "wallet not found"); }
  }
  private Transfer findByKey(String key) {
    var rows = jdbc.query("SELECT id, from_wallet_id, to_wallet_id, amount_paise, status, created_at FROM transfers WHERE idempotency_key = ?", transferMapper, key);
    return rows.isEmpty() ? null : rows.getFirst();
  }
  private Transfer findById(UUID id) {
    try { return jdbc.queryForObject("SELECT id, from_wallet_id, to_wallet_id, amount_paise, status, created_at FROM transfers WHERE id = ?", transferMapper, id); }
    catch (EmptyResultDataAccessException e) { throw new ApiException(404, "transfer not found"); }
  }
  private Credit findCreditByKey(String key) {
    var rows = jdbc.query("SELECT id, wallet_id, amount_paise, status, created_at FROM wallet_credits WHERE idempotency_key = ?", (rs, n) -> credit(rs), key);
    return rows.isEmpty() ? null : rows.getFirst();
  }
  private Credit findCreditById(UUID id) {
    try { return jdbc.queryForObject("SELECT id, wallet_id, amount_paise, status, created_at FROM wallet_credits WHERE id = ?", (rs, n) -> credit(rs), id); }
    catch (EmptyResultDataAccessException e) { throw new ApiException(404, "credit not found"); }
  }
  private void requireOwner(Wallet wallet, String caller) {
    if (!wallet.userId().equals(caller)) throw new ApiException(403, "wallet does not belong to caller");
  }
  private void countAfterCommit(Counter counter) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) { counter.increment(); return; }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { counter.increment(); }
    });
  }
  private Wallet wallet(ResultSet rs) throws SQLException { return new Wallet(rs.getObject("id", UUID.class), rs.getString("user_id"), rs.getString("upi_id"), rs.getLong("balance_paise")); }
  private Transfer transfer(ResultSet rs) throws SQLException { return new Transfer(rs.getObject("id", UUID.class), rs.getObject("from_wallet_id", UUID.class), rs.getObject("to_wallet_id", UUID.class), rs.getLong("amount_paise"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)); }
  private Credit credit(ResultSet rs) throws SQLException { return new Credit(rs.getObject("id", UUID.class), rs.getObject("wallet_id", UUID.class), rs.getLong("amount_paise"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)); }
  private WalletTransaction history(ResultSet rs) throws SQLException { return new WalletTransaction(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("direction"), rs.getLong("amount_paise"), rs.getString("status"), rs.getString("counterparty_upi_id"), rs.getObject("created_at", OffsetDateTime.class)); }
}
