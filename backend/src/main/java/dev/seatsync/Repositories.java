package dev.seatsync;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;

interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
    List<ShowEntity> findByTheatreIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(UUID theatreId, Instant from, Instant to);
    boolean existsByTheatreIdAndStartsAt(UUID theatreId, Instant startsAt);
}
interface CityRepository extends JpaRepository<CityEntity, UUID> { List<CityEntity> findAllByOrderByName(); }
interface TheatreRepository extends JpaRepository<TheatreEntity, UUID> { List<TheatreEntity> findByCityIdOrderByName(UUID cityId); }
interface UserRepository extends JpaRepository<UserEntity, UUID> { Optional<UserEntity> findByEmailIgnoreCase(String email); boolean existsByEmailIgnoreCase(String email); boolean existsByUsernameIgnoreCase(String username); }
interface SeatRepository extends JpaRepository<ShowSeat, UUID> {
    @Query("select s from ShowSeat s where s.show.id=:showId order by s.label") List<ShowSeat> byShow(@Param("showId") UUID showId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShowSeat s where s.show.id=:showId and s.id in :ids order by s.id") List<ShowSeat> lockSeats(@Param("showId") UUID showId, @Param("ids") Collection<UUID> ids);
}
interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    List<Reservation> findByStatusAndExpiresAtBefore(ReservationState state, Instant before);
    List<Reservation> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, ReservationState state);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id=:id") Optional<Reservation> lockById(@Param("id") UUID id);
}
interface ReservationSeatRepository extends JpaRepository<ReservationSeat, ReservationSeatId> {
    List<ReservationSeat> findByIdReservationId(UUID reservationId);
}
interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    Optional<Payment> findByReservationId(UUID reservationId);
}
