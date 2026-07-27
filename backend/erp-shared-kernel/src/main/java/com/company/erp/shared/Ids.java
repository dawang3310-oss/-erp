package com.company.erp.shared;

import java.security.SecureRandom;

public final class Ids {
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private Ids() {
  }

  public static String newId() {
    var value = new char[26];
    for (int index = 0; index < value.length; index++) {
      value[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(value);
  }
}
