package com.company.erp.inventory;

public record ReservationResult(String reservationId, String status, int availableAfter) {
  public static ReservationResult reserved(String reservationId, int availableAfter) {
    return new ReservationResult(reservationId, "RESERVED", availableAfter);
  }

  public static ReservationResult insufficient(int availableAfter) {
    return new ReservationResult(null, "INSUFFICIENT", availableAfter);
  }
}
