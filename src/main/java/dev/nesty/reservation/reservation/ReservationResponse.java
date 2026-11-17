package dev.nesty.reservation.reservation;

import java.time.LocalDateTime;

public record ReservationResponse(
        Long id,
        Long roomId,
        String roomName,
        String responsible,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ReservationStatus status) {
}
