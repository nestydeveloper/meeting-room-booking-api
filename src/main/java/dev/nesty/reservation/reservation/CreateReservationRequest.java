package dev.nesty.reservation.reservation;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateReservationRequest(
        @NotNull(message = "roomId is required")
        Long roomId,

        @NotBlank(message = "responsible must not be blank")
        @Size(max = 150, message = "responsible must be at most 150 characters")
        String responsible,

        @NotNull(message = "startTime is required")
        @FutureOrPresent(message = "startTime must not be in the past")
        LocalDateTime startTime,

        @NotNull(message = "endTime is required")
        @Future(message = "endTime must be in the future")
        LocalDateTime endTime) {
}
