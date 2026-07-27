package com.company.erp.pinduoduo.auth;

public interface TokenCipher {
  String encrypt(String plaintext);

  String decrypt(String ciphertext);
}
