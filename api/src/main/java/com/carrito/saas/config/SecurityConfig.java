package com.carrito.saas.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.carrito.saas.security.JwtFilter;

import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class SecurityConfig {

	private final TenantContextFilter requestContextFilter;
	private final JwtFilter jwtFilter;

	
	public SecurityConfig(TenantContextFilter requestContextFilter, JwtFilter jwtFilter) {
		this.requestContextFilter = requestContextFilter;
		this.jwtFilter = jwtFilter;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

	    http
	        .cors(cors -> {})
	        .csrf(csrf -> csrf.disable())

	        .sessionManagement(session ->
	            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
	        )

	        .authorizeHttpRequests(auth -> auth

	            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

	            .requestMatchers(
	                "/api/auth/**",
	                "/login/**",
	                "/login.html",
	                "/css/**",
	                "/js/**",
	                "/kds/**",
	                "/admin/**",
	                "/dashboard/**"
	            ).permitAll()

	            // Actuator (incluye Prometheus)
	            .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()

	            // Carta digital pública
	            .requestMatchers("/api/menu/**").permitAll()
	            .requestMatchers("/menu/**").permitAll()

	            // Restaurante público
	            .requestMatchers("/api/restaurants/slug/**").permitAll()

	            // Crear restaurantes
	            .requestMatchers("/api/restaurants/**").permitAll()

	            // Websocket
	            .requestMatchers("/ws/**").permitAll()

	            // Crear pedidos público
	            .requestMatchers(HttpMethod.POST, "/api/orders").permitAll()

	            .anyRequest().authenticated()
	        )

	        // ORDEN DE FILTROS (correcto y sin dependencia entre ellos)
	        // 1. Contexto (tenant + correlationId)
	        .addFilterBefore(requestContextFilter,
	            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)

	        // 2. JWT (auth + user)
	        .addFilterBefore(jwtFilter,
	            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);

	    return http.build();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {

		return config.getAuthenticationManager();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
	
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {

	    CorsConfiguration configuration = new CorsConfiguration();

	    configuration.addAllowedOrigin("http://localhost:8080");
	    configuration.addAllowedMethod("*");
	    configuration.addAllowedHeader("*");
	    configuration.setAllowCredentials(true);

	    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

	    source.registerCorsConfiguration("/**", configuration);

	    return source;
	}

}
