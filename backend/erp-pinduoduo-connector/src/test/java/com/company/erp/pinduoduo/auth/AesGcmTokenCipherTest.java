package com.company.erp.pinduoduo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AesGcmTokenCipherTest {
  private static final byte[] KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

  @Test
  void encryptedValueDoesNotContainPlaintextAndRoundTrips() {
    var cipher = new AesGcmTokenCipher(KEY);

    var encrypted = cipher.encrypt("access-secret");

    assertThat(encrypted).doesNotContain("access-secret");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("access-secret");
  }

  @Test
  void encryptingTheSameTokenTwiceUsesDifferentNonces() {
    var cipher = new AesGcmTokenCipher(KEY);

    assertThat(cipher.encrypt("same-token")).isNotEqualTo(cipher.encrypt("same-token"));
  }

  @Test
  void rejectsKeysThatAreNotExactly256Bits() {
    assertThatThrownBy(() -> new AesGcmTokenCipher(new byte[16]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("256-bit");
  }

  @Test
  void rejectsTamperedCiphertext() {
    var cipher = new AesGcmTokenCipher(KEY);
    var encrypted = cipher.encrypt("access-secret");
    var replacement = encrypted.endsWith("A") ? "B" : "A";
    var tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;

    assertThatThrownBy(() -> cipher.decrypt(tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decrypt");
  }
}
