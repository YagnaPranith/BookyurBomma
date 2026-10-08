package dev.seatsync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.security.Principal;

record CityDto(UUID id,String name,String state) {}
record TheatreDto(UUID id,UUID cityId,String city,String name,String address) {}
record ShowDto(UUID id,String title,UUID theatreId,String theatre,String city,Instant startsAt,Instant endsAt,int durationMinutes,int priceCents) {}
record SeatDto(UUID id, String label, SeatState state) {}
record ReservationRequest(@NotNull UUID showId, @NotEmpty @Size(max=8) List<@NotNull UUID> seatIds) {}
record ReservationDto(UUID id, UUID showId, List<UUID> seatIds, ReservationState status, Instant expiresAt) {}
record BookingHistoryDto(UUID reservationId,String title,String theatre,String city,Instant startsAt,List<String> seats,int amountCents,UUID paymentId) {}

@RestController @RequestMapping("/api/v1")
class SeatSyncController {
    private final CatalogService catalog;
    private final ReservationService reservations; private final BookingHistoryService history;
    SeatSyncController(CatalogService catalog, ReservationService reservations,BookingHistoryService history) { this.catalog=catalog; this.reservations=reservations;this.history=history; }
    @GetMapping("/areas") List<CityDto> areas() { return catalog.areas(); }
    @GetMapping("/theatres") List<TheatreDto> theatres(@RequestParam UUID cityId) { return catalog.theatres(cityId); }
    @GetMapping("/shows") List<ShowDto> shows(@RequestParam UUID theatreId,@RequestParam String date) { return catalog.shows(theatreId,java.time.LocalDate.parse(date)); }
    @GetMapping("/shows/{id}/seats") List<SeatDto> seats(@PathVariable UUID id) { return catalog.seats(id); }
    @PostMapping("/reservations") ResponseEntity<ReservationDto> reserve(@RequestHeader("Idempotency-Key") @Size(min=8,max=128) String key, @Valid @RequestBody ReservationRequest request,Principal user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reservations.reserve(key,request,user.getName()));
    }
    @GetMapping("/reservations/{id}") ReservationDto get(@PathVariable UUID id,Principal user) { return reservations.get(id,user.getName()); }
    @DeleteMapping("/reservations/{id}") ReservationDto cancel(@PathVariable UUID id,Principal user) { return reservations.cancel(id,user.getName()); }
    @GetMapping("/users/me/bookings") List<BookingHistoryDto> bookings(Principal user) { return history.forUser(user.getName()); }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException ex) { return problem(HttpStatus.BAD_REQUEST,ex.getMessage()); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<ProblemDetail> conflict(IllegalStateException ex) { return problem(HttpStatus.CONFLICT,ex.getMessage()); }
    @ExceptionHandler({RedisUnavailableException.class, org.springframework.data.redis.RedisConnectionFailureException.class, org.springframework.data.redis.RedisSystemException.class}) ResponseEntity<ProblemDetail> redisUnavailable(RuntimeException ex) { return problem(HttpStatus.SERVICE_UNAVAILABLE,"Reservation service is temporarily unavailable"); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<ProblemDetail> unavailable(Exception ex) { return problem(HttpStatus.SERVICE_UNAVAILABLE,"Reservation storage is temporarily unavailable"); }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String detail) { var p=ProblemDetail.forStatusAndDetail(status,detail); p.setTitle(status.getReasonPhrase()); return ResponseEntity.status(status).body(p); }
}
