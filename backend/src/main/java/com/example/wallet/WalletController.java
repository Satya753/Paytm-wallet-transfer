package com.example.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {
  private final WalletService service;
  public WalletController(WalletService service) { this.service = service; }

  @PostMapping("/wallets")
  @ResponseStatus(HttpStatus.CREATED)
  public WalletResponse create(HttpServletRequest request) {
    return WalletResponse.from(service.getOrCreateWallet(user(request)));
  }

  /** Backend-only session reset, useful for test runs and an explicit logout flow. */
  @PostMapping("/wallets/session/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    service.logout(user(request));
  }

  @GetMapping("/wallets/{id}")
  public WalletResponse get(@PathVariable String id, HttpServletRequest request) {
    return WalletResponse.from(service.getWallet(id, user(request)));
  }

  @PostMapping("/wallets/{id}/credits")
  @ResponseStatus(HttpStatus.CREATED)
  public CreditResponse credit(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest request) {
    long amount = amountPaise(body);
    return CreditResponse.from(service.credit(id, amount, body.path("idempotency_key").asText(null), user(request)), service);
  }

  @GetMapping("/wallets/{id}/transactions")
  public java.util.List<TransactionResponse> history(@PathVariable String id, HttpServletRequest request) {
    return service.history(id, user(request)).stream().map(TransactionResponse::from).toList();
  }

  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.CREATED)
  public TransferResponse send(@RequestBody JsonNode body, HttpServletRequest request) {
    if (body == null || !body.hasNonNull("from") || !body.hasNonNull("to"))
      throw new ApiException(400, "from, to and amount_paise are required");
    String from = body.get("from").asText();
    String to = body.get("to").asText();
    if (from.isBlank() || to.isBlank()) throw new ApiException(400, "from and to must be UPI IDs");
    String key = body.path("idempotency_key").asText(null);
    return TransferResponse.from(service.transfer(from, to, amountPaise(body), key, user(request)), service);
  }

  @GetMapping("/transfers/{id}")
  public TransferResponse getTransfer(@PathVariable UUID id, HttpServletRequest request) {
    return TransferResponse.from(service.getTransfer(id, user(request)), service);
  }

  private String user(HttpServletRequest request) { return (String) request.getAttribute("userId"); }
  private long amountPaise(JsonNode body) {
    if (body == null || !body.has("amount_paise") || !body.get("amount_paise").isIntegralNumber() || !body.get("amount_paise").canConvertToLong())
      throw new ApiException(400, "amount_paise must be an integer paise value");
    return body.get("amount_paise").longValue();
  }

  public record WalletResponse(String upi_id, long balance_paise, UUID session_id) {
    static WalletResponse from(WalletService.WalletSession session) {
      return new WalletResponse(session.wallet().upiId(), session.wallet().balancePaise(), session.sessionId());
    }
    static WalletResponse from(WalletService.Wallet w) { return new WalletResponse(w.upiId(), w.balancePaise(), null); }
  }
  public record TransferResponse(UUID id, String from, String to, long amount_paise, String status, Object created_at) {
    static TransferResponse from(WalletService.Transfer t, WalletService service) { return new TransferResponse(t.id(), service.upiId(t.from()), service.upiId(t.to()), t.amountPaise(), t.status(), t.createdAt()); }
  }
  public record CreditResponse(UUID id, String wallet_upi_id, long amount_paise, String status, Object created_at) {
    static CreditResponse from(WalletService.Credit c, WalletService service) { return new CreditResponse(c.id(), service.upiId(c.walletId()), c.amountPaise(), c.status(), c.createdAt()); }
  }
  public record TransactionResponse(UUID id, String type, String direction, long amount_paise, String status, String counterparty_upi_id, Object created_at) {
    static TransactionResponse from(WalletService.WalletTransaction t) { return new TransactionResponse(t.id(), t.type(), t.direction(), t.amountPaise(), t.status(), t.counterpartyUpiId(), t.createdAt()); }
  }
}
