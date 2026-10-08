package dev.seatsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SeatSyncApplication {
    public static void main(String[] args) { SpringApplication.run(SeatSyncApplication.class, args); }
}
