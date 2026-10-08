package dev.seatsync;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
class PaymentService {
    private final PaymentRepository payments;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final SeatRepository seats;
    private final ShowRepository shows;
    private final UserRepository users;

    PaymentService(PaymentRepository payments, ReservationRepository reservations,
                   ReservationSeatRepository reservationSeats, SeatRepository seats, ShowRepository shows,UserRepository users) {
        this.payments=payments; this.reservations=reservations; this.reservationSeats=reservationSeats; this.seats=seats; this.shows=shows;this.users=users;
    }

    @Transactional
    PaymentDto pay(String key,PaymentRequest request,String email) {
        var user=users.findByEmailIgnoreCase(email).orElseThrow(()->new IllegalArgumentException("Account not found"));
        var byKey=payments.findByIdempotencyKey(key);
        if(byKey.isPresent()) {
            if(!byKey.get().reservationId.equals(request.reservationId())||!owns(byKey.get().reservationId,user.id)) throw new IllegalStateException("Idempotency key was already used for another payment");
            return dto(byKey.get());
        }
        var reservation=reservations.lockById(request.reservationId()).orElseThrow(()->new IllegalArgumentException("Reservation not found"));
        if(!user.id.equals(reservation.userId)) throw new IllegalArgumentException("Reservation not found");
        var existing=payments.findByReservationId(reservation.id);
        if(existing.isPresent()) return dto(existing.get());
        if(reservation.status!=ReservationState.PENDING) throw new IllegalStateException("Reservation is no longer awaiting payment");

        PaymentStatus outcome=request.outcome();
        if(!reservation.expiresAt.isAfter(Instant.now())) {
            outcome=PaymentStatus.TIMEOUT;
            releaseSeats(reservation,ReservationState.EXPIRED);
        } else if(outcome==PaymentStatus.SUCCESS) {
            reservation.status=ReservationState.CONFIRMED;
            reservationSeats.findByIdReservationId(reservation.id).forEach(link -> seats.findById(link.id.seatId).ifPresent(seat -> seat.state=SeatState.BOOKED));
        } else {
            releaseSeats(reservation,ReservationState.RELEASED);
        }
        int amount=shows.findById(reservation.showId).orElseThrow(()->new IllegalArgumentException("Show not found")).priceCents
                * reservationSeats.findByIdReservationId(reservation.id).size();
        var payment=payments.save(new Payment(UUID.randomUUID(),reservation.id,key,outcome,amount));
        return dto(payment);
    }

    @Transactional(readOnly=true)
    PaymentDto get(UUID id,String email) {
        var payment=payments.findById(id).orElseThrow(()->new IllegalArgumentException("Payment not found"));
        var user=users.findByEmailIgnoreCase(email).orElseThrow(()->new IllegalArgumentException("Account not found"));
        if(!owns(payment.reservationId,user.id)) throw new IllegalArgumentException("Payment not found");
        return dto(payment);
    }

    private boolean owns(UUID reservationId,UUID userId) { return reservations.findById(reservationId).map(r->userId.equals(r.userId)).orElse(false); }

    private void releaseSeats(Reservation reservation, ReservationState state) {
        reservation.status=state;
        reservationSeats.findByIdReservationId(reservation.id).forEach(link -> seats.findById(link.id.seatId).ifPresent(seat -> {
            if(seat.state==SeatState.RESERVED) seat.state=SeatState.AVAILABLE;
        }));
    }
    private PaymentDto dto(Payment payment) { return new PaymentDto(payment.id,payment.reservationId,payment.status,payment.amountCents,payment.createdAt); }
}
