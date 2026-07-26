package com.company.erp.pinduoduo.auth;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmTokenCipher implements TokenCipher {
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random;

  public AesGcmTokenCipher(byte[] keyBytes) {
    if (keyBytes == null || keyBytes.length != 32) {
      throw new IllegalArgumentException("A 256-bit token encryption key is required");
    }
    this.key = new SecretKeySpec(Arrays.copyOf(keyBytes, keyBytes.length), "AES");
    this.random = new SecureRandom();
  }

  @Override
  public String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      throw new IllegalArgumentException("Token plaintext is required");
    }
    try {
      var nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      var encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var envelope = new byte[nonce.length + encrypted.length];
      System.arraycopy(nonce, 0, envelope, 0, nonce.length);
      System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(envelope);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to encrypt token", exception);
    }
  }

  @Override
  public String decrypt(String ciphertext) {
    if (ciphertext == null || ciphertext.isBlank()) {
      throw new IllegalArgumentException("Token ciphertext is required");
    }
    try {
      var envelope = Base64.getDecoder().decode(ciphertext);
      if (envelope.length <= NONCE_BYTES) {
        throw new IllegalArgumentException("Unable to decrypt token");
      }
      var nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
      var encrypted = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(
          cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unable to decrypt token", exception);
    }
  }
}
