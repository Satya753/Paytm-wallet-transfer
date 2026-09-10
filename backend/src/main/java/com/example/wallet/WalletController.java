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

  @GetMapping("/wallets/{id}")
  public WalletResponse get(@PathVariable UUID id, HttpServletRequest request) {
    return WalletResponse.from(service.getWallet(id, user(request)));
  }

  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.CREATED)
  public TransferResponse send(@RequestBody JsonNode body, HttpServletRequest request) {
    if (body == null || !body.hasNonNull("from") || !body.hasNonNull("to") || !body.has("amount_paise"))
      throw new ApiException(400, "from, to and amount_paise are required");
    if (!body.get("amount_paise").isIntegralNumber() || !body.get("amount_paise").canConvertToLong())
      throw new ApiException(400, "amount_paise must be an integer paise value");
    try {
      UUID from = UUID.fromString(body.get("from").asText());
      UUID to = UUID.fromString(body.get("to").asText());
      String key = body.path("idempotency_key").asText(null);
      return TransferResponse.from(service.transfer(from, to, body.get("amount_paise").longValue(), key, user(request)));
    } catch (IllegalArgumentException e) { throw new ApiException(400, "from and to must be UUIDs"); }
  }

  @GetMapping("/transfers/{id}")
  public TransferResponse getTransfer(@PathVariable UUID id, HttpServletRequest request) {
    return TransferResponse.from(service.getTransfer(id, user(request)));
  }

  private String user(HttpServletRequest request) { return (String) request.getAttribute("userId"); }

  public record WalletResponse(UUID id, long balance_paise) {
    static WalletResponse from(WalletService.Wallet w) { return new WalletResponse(w.id(), w.balancePaise()); }
  }
  public record TransferResponse(UUID id, UUID from, UUID to, long amount_paise, String status, Object created_at) {
    static TransferResponse from(WalletService.Transfer t) { return new TransferResponse(t.id(), t.from(), t.to(), t.amountPaise(), t.status(), t.createdAt()); }
  }
}
