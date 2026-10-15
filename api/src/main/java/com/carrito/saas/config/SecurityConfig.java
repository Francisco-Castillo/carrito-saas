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

	            // Shell de la SPA (Vite) y assets publicos que referencia:
	            // sin esto el bundle en /assets/** responde 403 y ninguna
	            // pantalla carga, porque cualquier ruta no listada exige token.
	            .requestMatchers(
	                "/",
	                "/index.html",
	                "/assets/**",
	                "/favicon.svg",
	                "/favicon.ico",
	                "/ding.mp3",
	                "/ready.mp3",
	                // Fotos de demo del seed (docs/dev-seed.sql): son assets
	                // publicos de la carta, igual que las imagenes de producto
	                // que subiria un negocio real.
	                "/dev-media/**"
	            ).permitAll()

	            // Actuator (incluye Prometheus)
	            .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()

	            // Carta digital pública
	            .requestMatchers("/api/menu/**").permitAll()
	            .requestMatchers("/menu/**").permitAll()

	            // Restaurante público (slug lookup del menú público y login;
	            // el slug es público porque va impreso en el QR)
	            .requestMatchers("/api/restaurants/slug/**").permitAll()

	            // Todo lo demás bajo /api/restaurants/** (dashboard, analytics,
	            // users, QR, tables) exige token: los KPIs del negocio no pueden
	            // ser públicos solo porque el slug lo sea.

	            // Websocket
	            .requestMatchers("/ws/**").permitAll()

	            // Crear pedidos público
	            .requestMatchers(HttpMethod.POST, "/api/orders", "/api/orders/menu/{slug}").permitAll()

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
