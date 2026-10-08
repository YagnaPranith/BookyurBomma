package dev.seatsync;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Configuration
class DemoData {
    private static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    private static final List<LocalTime> TIMES=List.of(LocalTime.of(9,0),LocalTime.of(12,30),LocalTime.of(16,0),LocalTime.of(19,30),LocalTime.of(23,0));
    private static final List<String> TITLES=List.of("Coastal Stories","The Last Signal","Midnight Express");
    @Bean CommandLineRunner seedSchedule(TheatreRepository theatres,ShowRepository shows,JdbcTemplate jdbc) {
        return args -> {
            var newShows=new ArrayList<ShowEntity>();var today=LocalDate.now(ZONE);
            for(var theatre:theatres.findAll()) for(int day=0;day<14;day++) for(int slot=0;slot<TIMES.size();slot++) {
                var date=today.plusDays(day);var starts=date.atTime(TIMES.get(slot)).atZone(ZONE).toInstant();
                var showId=UUID.nameUUIDFromBytes((theatre.id+":"+date+":"+slot).getBytes(StandardCharsets.UTF_8));
                if(!shows.existsById(showId)) newShows.add(new ShowEntity(showId,TITLES.get((day+slot)%TITLES.size()),theatre,starts,slot<2?249900:199900));
            }
            if(newShows.isEmpty()) return;
            shows.saveAll(newShows);
            var seatRows=new ArrayList<Object[]>(newShows.size()*40);
            for(var show:newShows) for(int row=0;row<5;row++) for(int col=1;col<=8;col++) {
                String label=""+(char)('A'+row)+col;UUID seatId=UUID.nameUUIDFromBytes((show.id+":"+label).getBytes(StandardCharsets.UTF_8));
                seatRows.add(new Object[]{seatId,show.id,label});
            }
            jdbc.batchUpdate("INSERT INTO show_seats(id,show_id,label,state) VALUES (?,?,?,'AVAILABLE') ON CONFLICT (id) DO NOTHING",seatRows,500,(ps,row)->{
                ps.setObject(1,row[0]);ps.setObject(2,row[1]);ps.setString(3,(String)row[2]);
            });
        };
    }
}
