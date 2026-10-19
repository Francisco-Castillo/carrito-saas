package com.carrito.saas.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj inyectable: el "today" de este código es server-local (dashboard,
 * timestamps de pedidos, y ahora el estado de apertura que evalúa
 * createOrder), así que el bean vive en la zona del servidor. Existe como
 * bean para que los tests fijen el tiempo (reloj fijo) sin tocar el sistema.
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
