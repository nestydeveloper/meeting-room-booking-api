package dev.nesty.reservation.reservation;

import dev.nesty.reservation.common.PageResponse;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

public interface ReservationService {

    ReservationResponse create(CreateReservationRequest request);

    PageResponse<ReservationResponse> search(Long roomId, LocalDate date, Pageable pageable);

    void cancel(Long id);
}
