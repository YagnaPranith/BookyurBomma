package dev.seatsync;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="app_users")
class UserEntity {
    @Id UUID id;
    @Column(nullable=false,unique=true,length=80) String username;
    @Column(nullable=false,unique=true,length=254) String email;
    @Column(name="password_hash",nullable=false) String passwordHash;
    @Column(nullable=false,length=24) String mobile;
    @Column(name="created_at",nullable=false) Instant createdAt;
    protected UserEntity() {}
    UserEntity(UUID id,String username,String email,String passwordHash,String mobile) { this.id=id;this.username=username;this.email=email;this.passwordHash=passwordHash;this.mobile=mobile;this.createdAt=Instant.now(); }
}

@Entity @Table(name="cities")
class CityEntity {
    @Id UUID id;
    @Column(nullable=false) String name;
    @Column(nullable=false) String state;
    protected CityEntity() {}
}

@Entity @Table(name="theatres")
class TheatreEntity {
    @Id UUID id;
    @Column(name="city_id",nullable=false) UUID cityId;
    @Column(nullable=false) String name;
    @Column(nullable=false) String address;
    protected TheatreEntity() {}
}

@Entity @Table(name="shows")
class ShowEntity {
    @Id UUID id;
    String title;
    String venue;
    @Column(name="starts_at") Instant startsAt;
    @Column(name="price_cents") int priceCents;
    @Column(name="theatre_id") UUID theatreId;
    @Column(name="duration_minutes",nullable=false) int durationMinutes=180;
    protected ShowEntity() {}
    ShowEntity(UUID id, String title, String venue, Instant startsAt, int priceCents) {
        this.id=id; this.title=title; this.venue=venue; this.startsAt=startsAt; this.priceCents=priceCents;
    }
    ShowEntity(UUID id, String title, TheatreEntity theatre, Instant startsAt, int priceCents) {
        this.id=id; this.title=title; this.venue=theatre.name; this.theatreId=theatre.id; this.startsAt=startsAt; this.priceCents=priceCents; this.durationMinutes=180;
    }
}

@Entity @Table(name="show_seats", uniqueConstraints=@UniqueConstraint(columnNames={"show_id","label"}))
class ShowSeat {
    @Id UUID id;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="show_id", nullable=false) ShowEntity show;
    String label;
    @Enumerated(EnumType.STRING) @Column(nullable=false) SeatState state=SeatState.AVAILABLE;
    protected ShowSeat() {}
    ShowSeat(UUID id, ShowEntity show, String label) { this.id=id; this.show=show; this.label=label; }
}
enum SeatState { AVAILABLE, RESERVED, BOOKED }

@Entity @Table(name="reservations")
class Reservation {
    @Id UUID id;
    @Column(name="show_id", nullable=false) UUID showId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) ReservationState status;
    @Column(name="expires_at", nullable=false) Instant expiresAt;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="user_id") UUID userId;
    protected Reservation() {}
    Reservation(UUID id, UUID showId, Instant expiresAt) { this(id,showId,expiresAt,null); }
    Reservation(UUID id, UUID showId, Instant expiresAt, UUID userId) { this.id=id; this.showId=showId; this.expiresAt=expiresAt; this.createdAt=Instant.now(); this.status=ReservationState.PENDING; this.userId=userId; }
}
enum ReservationState { PENDING, CONFIRMED, RELEASED, EXPIRED }

@Entity @Table(name="payments")
class Payment {
    @Id UUID id;
    @Column(name="reservation_id", nullable=false, unique=true) UUID reservationId;
    @Column(name="idempotency_key", nullable=false, unique=true, length=128) String idempotencyKey;
    @Enumerated(EnumType.STRING) @Column(nullable=false) PaymentStatus status;
    @Column(name="amount_cents", nullable=false) int amountCents;
    @Column(name="created_at", nullable=false) Instant createdAt;
    protected Payment() {}
    Payment(UUID id, UUID reservationId, String idempotencyKey, PaymentStatus status, int amountCents) {
        this.id=id; this.reservationId=reservationId; this.idempotencyKey=idempotencyKey; this.status=status; this.amountCents=amountCents; this.createdAt=Instant.now();
    }
}
enum PaymentStatus { SUCCESS, FAILED, TIMEOUT }

@Entity @Table(name="reservation_seats")
class ReservationSeat {
    @EmbeddedId ReservationSeatId id;
    @Column(name="show_id", nullable=false) UUID showId;
    protected ReservationSeat() {}
    ReservationSeat(UUID reservationId, UUID showId, UUID seatId) { this.id=new ReservationSeatId(reservationId,seatId); this.showId=showId; }
}
@Embeddable class ReservationSeatId implements java.io.Serializable {
    @Column(name="reservation_id") UUID reservationId;
    @Column(name="seat_id") UUID seatId;
    protected ReservationSeatId() {}
    ReservationSeatId(UUID reservationId, UUID seatId) { this.reservationId=reservationId; this.seatId=seatId; }
    @Override public boolean equals(Object o) { return o instanceof ReservationSeatId x && java.util.Objects.equals(reservationId,x.reservationId) && java.util.Objects.equals(seatId,x.seatId); }
    @Override public int hashCode() { return java.util.Objects.hash(reservationId,seatId); }
}
