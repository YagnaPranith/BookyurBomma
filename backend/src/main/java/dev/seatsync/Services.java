package dev.seatsync;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
class CatalogService {
    private final ShowRepository shows; private final SeatRepository seats; private final CityRepository cities; private final TheatreRepository theatres;
    CatalogService(ShowRepository shows,SeatRepository seats,CityRepository cities,TheatreRepository theatres) { this.shows=shows;this.seats=seats;this.cities=cities;this.theatres=theatres; }
    List<CityDto> areas() { return cities.findAllByOrderByName().stream().map(c->new CityDto(c.id,c.name,c.state)).toList(); }
    List<TheatreDto> theatres(UUID cityId) {
        var city=cities.findById(cityId).orElseThrow(()->new IllegalArgumentException("Area not found"));
        return theatres.findByCityIdOrderByName(cityId).stream().map(t->new TheatreDto(t.id,city.id,city.name,t.name,t.address)).toList();
    }
    List<ShowDto> shows(UUID theatreId,java.time.LocalDate date) {
        var theatre=theatres.findById(theatreId).orElseThrow(()->new IllegalArgumentException("Theatre not found"));
        var city=cities.findById(theatre.cityId).orElseThrow(()->new IllegalArgumentException("Area not found"));
        var zone=java.time.ZoneId.of("Asia/Kolkata"); var from=date.atStartOfDay(zone).toInstant(); var to=date.plusDays(1).atStartOfDay(zone).toInstant();
        return shows.findByTheatreIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(theatreId,from,to).stream()
                .map(s->new ShowDto(s.id,s.title,theatre.id,theatre.name,city.name,s.startsAt,s.startsAt.plus(Duration.ofMinutes(s.durationMinutes)),s.durationMinutes,s.priceCents)).toList();
    }
    List<SeatDto> seats(UUID id) { if(!shows.existsById(id)) throw new IllegalArgumentException("Show not found"); return seats.byShow(id).stream().map(s->new SeatDto(s.id,s.label,s.state)).toList(); }
}

@Service
class ReservationService {
    private static final Duration HOLD=Duration.ofMinutes(5), LOCK_TTL=Duration.ofSeconds(8);
    private final ReservationRepository reservations; private final ReservationSeatRepository reservationSeats;
    private final SeatRepository seats; private final ShowRepository shows; private final UserRepository users; private final StringRedisTemplate redis; private final ObjectMapper mapper; private final TransactionTemplate transactions;
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    ReservationService(ReservationRepository reservations,ReservationSeatRepository reservationSeats,SeatRepository seats,ShowRepository shows,UserRepository users,StringRedisTemplate redis,ObjectMapper mapper,PlatformTransactionManager txManager) {
        this.reservations=reservations;this.reservationSeats=reservationSeats;this.seats=seats;this.shows=shows;this.users=users;this.redis=redis;this.mapper=mapper;this.transactions=new TransactionTemplate(txManager);
    }
    ReservationDto reserve(String key, ReservationRequest req,String email) {
        var user=users.findByEmailIgnoreCase(email).orElseThrow(()->new IllegalArgumentException("Account not found"));
        if(new HashSet<>(req.seatIds()).size()!=req.seatIds().size()) throw new IllegalArgumentException("Duplicate seat IDs are not allowed");
        if(!shows.existsById(req.showId())) throw new IllegalArgumentException("Show not found");
        var ids=req.seatIds().stream().sorted().toList(); var token=UUID.randomUUID().toString(); var acquired=new ArrayList<String>();
        try {
            String idemLock="seatsync:idem-lock:"+key;
            Boolean idemAcquired=redis.opsForValue().setIfAbsent(idemLock,token,Duration.ofMinutes(2));
            long waitUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Boolean.TRUE.equals(idemAcquired)&&System.nanoTime()<waitUntil) {
                try { Thread.sleep(50); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Reservation interrupted; retry with the same idempotency key"); }
                idemAcquired=redis.opsForValue().setIfAbsent(idemLock,token,Duration.ofMinutes(2));
            }
            if(!Boolean.TRUE.equals(idemAcquired)) throw new IllegalStateException("This idempotency key is being processed; retry shortly");
            acquired.add(idemLock);
            String idemResult="seatsync:idem-result:"+key;
            String cached=redis.opsForValue().get(idemResult);
            String hash=requestHash(req,user.id);
            if(cached!=null) {
                String[] pieces=cached.split("\\n",2);
                if(pieces.length!=2||!pieces[0].equals(hash)) throw new IllegalStateException("Idempotency key was already used for a different request");
                try { return mapper.readValue(pieces[1],ReservationDto.class); } catch(Exception e) { throw new IllegalStateException("Could not read prior idempotent result"); }
            }
            for(UUID seatId:ids) {
                String redisKey="seatsync:show:"+req.showId()+":seat:"+seatId;
                Boolean ok=redis.opsForValue().setIfAbsent(redisKey,token,LOCK_TTL);
                if(!Boolean.TRUE.equals(ok)) throw new IllegalStateException("One or more seats are temporarily held or unavailable");
                acquired.add(redisKey);
            }
            ReservationDto result=transactions.execute(status -> reserveTransaction(req.showId(),ids,user.id));
            try { redis.opsForValue().set(idemResult,hash+"\n"+mapper.writeValueAsString(result),Duration.ofHours(24)); }
            catch(Exception e) { throw new RedisUnavailableException(); }
            return result;
        } catch(org.springframework.data.redis.RedisSystemException ex) {
            throw new RedisUnavailableException();
        } finally {
            for(String redisKey:acquired) redis.execute(UNLOCK,List.of(redisKey),token);
        }
    }
    private String requestHash(ReservationRequest req,UUID userId) {
        try { var bytes=MessageDigest.getInstance("SHA-256").digest((userId+":"+req.showId()+":"+req.seatIds().stream().sorted().toList()).getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(bytes); }
        catch(Exception e) { throw new IllegalStateException("Hash unavailable"); }
    }
    ReservationDto reserveTransaction(UUID showId,List<UUID> ids,UUID userId) {
        var rows=seats.lockSeats(showId,ids);
        if(rows.size()!=ids.size()) throw new IllegalArgumentException("A requested seat does not belong to this show");
        if(rows.stream().anyMatch(s->s.state!=SeatState.AVAILABLE)) throw new IllegalStateException("One or more seats are unavailable");
        var reservation=new Reservation(UUID.randomUUID(),showId,Instant.now().plus(HOLD),userId);
        reservations.save(reservation);
        rows.forEach(s->s.state=SeatState.RESERVED);
        reservationSeats.saveAll(rows.stream().map(s->new ReservationSeat(reservation.id,showId,s.id)).toList());
        return dto(reservation,ids);
    }
    @Transactional(readOnly=true) ReservationDto get(UUID id,String email) { var r=owned(id,email); return dto(r,reservationSeats.findByIdReservationId(id).stream().map(x->x.id.seatId).toList()); }
    @Transactional ReservationDto cancel(UUID id,String email) { var r=owned(id,email); release(r,ReservationState.RELEASED); return get(id,email); }
    @Scheduled(fixedDelay=10000) @Transactional void expireReservations() {
        for(var r:reservations.findByStatusAndExpiresAtBefore(ReservationState.PENDING,Instant.now())) release(r,ReservationState.EXPIRED);
    }
    private void release(Reservation r,ReservationState state) {
        if(r.status!=ReservationState.PENDING) return;
        for(var link:reservationSeats.findByIdReservationId(r.id)) seats.findById(link.id.seatId).ifPresent(s->{if(s.state==SeatState.RESERVED)s.state=SeatState.AVAILABLE;});
        r.status=state;
    }
    private Reservation owned(UUID id,String email) {
        var r=reservations.findById(id).orElseThrow(()->new IllegalArgumentException("Reservation not found"));
        var user=users.findByEmailIgnoreCase(email).orElseThrow(()->new IllegalArgumentException("Account not found"));
        if(!user.id.equals(r.userId)) throw new IllegalArgumentException("Reservation not found");
        return r;
    }
    private ReservationDto dto(Reservation r,List<UUID> ids) { return new ReservationDto(r.id,r.showId,ids,r.status,r.expiresAt); }
}

class RedisUnavailableException extends RuntimeException { RedisUnavailableException(){super("Reservation service is temporarily unavailable");} }

@Service
class BookingHistoryService {
    private final UserRepository users;private final ReservationRepository reservations;private final ReservationSeatRepository reservationSeats;private final SeatRepository seats;private final ShowRepository shows;private final TheatreRepository theatres;private final CityRepository cities;private final PaymentRepository payments;
    BookingHistoryService(UserRepository users,ReservationRepository reservations,ReservationSeatRepository reservationSeats,SeatRepository seats,ShowRepository shows,TheatreRepository theatres,CityRepository cities,PaymentRepository payments) {
        this.users=users;this.reservations=reservations;this.reservationSeats=reservationSeats;this.seats=seats;this.shows=shows;this.theatres=theatres;this.cities=cities;this.payments=payments;
    }
    @Transactional(readOnly=true) List<BookingHistoryDto> forUser(String email) {
        var user=users.findByEmailIgnoreCase(email).orElseThrow(()->new IllegalArgumentException("Account not found"));
        return reservations.findByUserIdAndStatusOrderByCreatedAtDesc(user.id,ReservationState.CONFIRMED).stream().map(r->{
            var show=shows.findById(r.showId).orElseThrow();var theatre=theatres.findById(show.theatreId).orElseThrow();var city=cities.findById(theatre.cityId).orElseThrow();
            var labels=reservationSeats.findByIdReservationId(r.id).stream().map(link->seats.findById(link.id.seatId).map(s->s.label).orElse("")).toList();
            var payment=payments.findByReservationId(r.id).orElseThrow();
            return new BookingHistoryDto(r.id,show.title,theatre.name,city.name,show.startsAt,labels,payment.amountCents,payment.id);
        }).toList();
    }
}
