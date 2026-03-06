package com.carrito.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {
        "com.carrito",
        "com.carrito"
})
@EntityScan("com.carrito")
@EnableJpaRepositories("com.carrito")
public class CarritoSaasApplication {
    public static void main(String[] args) {
        SpringApplication.run(CarritoSaasApplication.class, args);
    }
}