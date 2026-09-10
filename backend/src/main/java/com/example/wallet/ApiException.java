package com.example.wallet;

public class ApiException extends RuntimeException {
  final int status;
  public ApiException(int status, String message) { super(message); this.status = status; }
}
