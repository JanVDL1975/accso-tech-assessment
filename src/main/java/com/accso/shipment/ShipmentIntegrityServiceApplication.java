package com.accso.shipment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShipmentIntegrityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShipmentIntegrityServiceApplication.class, args);
    }
}
