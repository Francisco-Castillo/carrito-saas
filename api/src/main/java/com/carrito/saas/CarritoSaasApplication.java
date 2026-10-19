package com.carrito.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.carrito",
        "com.carrito"
})
@EntityScan("com.carrito")
@EnableJpaRepositories("com.carrito")
// Sin @EnableScheduling Spring nunca registra el
// ScheduledAnnotationBeanPostProcessor y todos los @Scheduled del módulo jobs
// (ProductMetricsScheduler, ProductTrendScheduler, PredictionScheduler) son
// dead code: la cadena de analítica nunca correría sola.
@EnableScheduling
public class CarritoSaasApplication {
    public static void main(String[] args) {
        SpringApplication.run(CarritoSaasApplication.class, args);
    }
}
