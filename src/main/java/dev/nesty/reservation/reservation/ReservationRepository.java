package dev.nesty.reservation.reservation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    /**
     * Redeclared only to attach {@code @EntityGraph}: every listed reservation renders its room
     * name, so the room is fetched with the page instead of one query per distinct room. The count
     * query is unaffected, because Spring Data applies fetch graphs to the data query only.
     */
    @Override
    @EntityGraph(attributePaths = "room")
    Page<Reservation> findAll(Specification<Reservation> spec, Pageable pageable);

    /**
     * Half-open overlap: an existing booking clashes when it starts before the new one ends
     * and ends after the new one starts. A booking that ends at 10:00 does not clash with one
     * that starts at 10:00. Cancelled bookings never block a slot.
     */
    default boolean existsActiveOverlapping(Long roomId, LocalDateTime start, LocalDateTime end) {
        return countOverlapping(roomId, start, end, ReservationStatus.ACTIVE) > 0;
    }

    @Query("""
            select count(r)
            from Reservation r
            where r.room.id = :roomId
              and r.status = :status
              and r.startTime < :end
              and r.endTime > :start
            """)
    long countOverlapping(@Param("roomId") Long roomId,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end,
                          @Param("status") ReservationStatus status);

    @Query("select r from Reservation r where r.id = :id and r.status = :status")
    java.util.Optional<Reservation> findByIdAndStatus(@Param("id") Long id,
                                                     @Param("status") ReservationStatus status);

    Page<Reservation> findAllByStatus(ReservationStatus status, Pageable pageable);
}
