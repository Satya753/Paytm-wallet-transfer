package com.example.wallet;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {
  private final JdbcTemplate jdbc;
  private final RowMapper<Wallet> walletMapper = (rs, n) -> wallet(rs);
  private final RowMapper<Transfer> transferMapper = (rs, n) -> transfer(rs);

  public WalletService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public record Wallet(UUID id, String userId, long balancePaise) {}
  public record Transfer(UUID id, UUID from, UUID to, long amountPaise, String status, OffsetDateTime createdAt) {}

  @Transactional
  public Wallet getOrCreateWallet(String userId) {
    UUID id = UUID.randomUUID();
    return jdbc.queryForObject("""
        INSERT INTO wallets (id, user_id) VALUES (?, ?)
        ON CONFLICT (user_id) DO UPDATE SET user_id = EXCLUDED.user_id
        RETURNING id, user_id, balance_paise
        """, walletMapper, id, userId);
  }

  public Wallet getWallet(UUID id, String caller) {
    Wallet wallet = walletById(id, false);
    requireOwner(wallet, caller);
    return wallet;
  }

  @Transactional
  public Transfer transfer(UUID from, UUID to, long amount, String key, String caller) {
    if (amount <= 0) throw new ApiException(400, "amount_paise must be a positive integer");
    if (from.equals(to)) throw new ApiException(400, "from and to must differ");
    if (key == null || key.isBlank() || key.length() > 255) throw new ApiException(400, "invalid idempotency_key");

    // Validate authorization before reserving a globally unique idempotency key.
    requireOwner(walletById(from, false), caller);
    walletById(to, false);
    UUID id = UUID.randomUUID();
    var inserted = jdbc.queryForList("""
            INSERT INTO transfers (id, idempotency_key, from_wallet_id, to_wallet_id, amount_paise, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """, UUID.class, id, key, from, to, amount);
    if (inserted.isEmpty()) {
      Transfer existing = findByKey(key);
      requireOwner(walletById(existing.from(), false), caller);
      if (!existing.from().equals(from) || !existing.to().equals(to) || existing.amountPaise() != amount)
        throw new ApiException(409, "idempotency_key was already used with a different request");
      return existing;
    }

    // Locks are always acquired in UUID order, preventing opposite-direction transfer deadlocks.
    Wallet first = walletById(from.compareTo(to) < 0 ? from : to, true);
    Wallet second = walletById(from.compareTo(to) < 0 ? to : from, true);
    Wallet source = first.id().equals(from) ? first : second;
    requireOwner(source, caller);
    if (source.balancePaise() < amount) {
      jdbc.update("UPDATE transfers SET status = 'REJECTED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
      return findById(id);
    }
    jdbc.update("UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ?", amount, from);
    jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?", amount, to);
    jdbc.update("UPDATE transfers SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    return findById(id);
  }

  public Transfer getTransfer(UUID id, String caller) {
    Transfer transfer = findById(id);
    requireOwner(walletById(transfer.from(), false), caller);
    return transfer;
  }

  private Wallet walletById(UUID id, boolean lock) {
    try { return jdbc.queryForObject("SELECT id, user_id, balance_paise FROM wallets WHERE id = ?" + (lock ? " FOR UPDATE" : ""), walletMapper, id); }
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
  private void requireOwner(Wallet wallet, String caller) {
    if (!wallet.userId().equals(caller)) throw new ApiException(403, "wallet does not belong to caller");
  }
  private Wallet wallet(ResultSet rs) throws SQLException { return new Wallet(rs.getObject("id", UUID.class), rs.getString("user_id"), rs.getLong("balance_paise")); }
  private Transfer transfer(ResultSet rs) throws SQLException { return new Transfer(rs.getObject("id", UUID.class), rs.getObject("from_wallet_id", UUID.class), rs.getObject("to_wallet_id", UUID.class), rs.getLong("amount_paise"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)); }
}
