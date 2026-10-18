package com.carrito.saas.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
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

	            // El contenedor re-despacha a /error las respuestas de seguridad SIN
	            // cuerpo (el 403 del AccessDeniedHandler, el 401 del entry point). En
	            // ese segundo paso el filtro de JWT ya no corre —actúa una vez por
	            // request— así que el contexto es ANÓNIMO. Sin esta regla la cadena
	            // deniega su propio renderizado de error y el entry point PISA el
	            // status real: un 403 legítimo de un usuario autenticado salía 401.
	            // El bug era latente mientras TODA respuesta de seguridad fue 403
	            // (el pisotón daba el mismo código) y quedó a la vista al separar
	            // 401 (anónimo) de 403 (autenticado sin el rol exigido). MockMvc no
	            // pasa por el contenedor, así que no hace ese re-despacho: lo fija
	            // SecurityErrorDispatchTests y lo verifica la prueba en vivo.
	            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

	            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

	            .requestMatchers(
	                "/api/auth/**",
	                "/login/**",
	                "/login.html",
	                // Rutas limpias del SPA (SpaRoutingConfig las sirve con forward
	                // a los mismos archivos estáticos): el path exacto no matchea
	                // /xxx/** con la semántica de PathPattern, así que se nombran
	                // explícitamente para quedarse públicas como sus .html.
	                "/login",
	                "/css/**",
	                "/js/**",
	                "/kds/**",
	                "/kds",
	                "/admin/**",
	                "/admin",
	                "/dashboard/**",
	                "/dashboard",
	                "/pedidos/**",
	                "/pedidos"
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
	            .requestMatchers("/menu", "/menu/").permitAll()

	            // Restaurante público (slug lookup del menú público y login;
	            // el slug es público porque va impreso en el QR)
	            .requestMatchers("/api/restaurants/slug/**").permitAll()

	            // Todo lo demás bajo /api/restaurants/** (dashboard, analytics,
	            // users, QR, tables) exige token: los KPIs del negocio no pueden
	            // ser públicos solo porque el slug lo sea.

	            // Websocket
	            .requestMatchers("/ws/**").permitAll()

	            // Crear pedidos público (con slug por compatibilidad de QR viejos y
	            // sin slug: deployment de un solo negocio, la carta en /menu limpio)
	            .requestMatchers(HttpMethod.POST, "/api/orders", "/api/orders/menu", "/api/orders/menu/{slug}").permitAll()

	            // Barrera de PRIVILEGIO (no de tenant): POST /api/predictions/run
	            // dispara el recálculo GLOBAL de predicciones (todos los negocios),
	            // no un job del negocio propio. Solo OWNER/ADMIN pueden lanzarlo.
	            // Honestidad del alcance: el job es global, así que un OWNER del
	            // negocio A que lo invoca sigue recalculando las predicciones de
	            // TODOS los negocios — esta regla no aísla negocios, solo limita
	            // quién puede accionar el recálculo.
	            // Un anónimo recibe 401 (entry point de la cadena, contrato
	            // resuelto en odd/tasks/http-error-contract.md); un AUTENTICADO
	            // sin OWNER/ADMIN recibe 403, que es el caso que esta regla
	            // castiga.
	            // Consola de pedidos (S5 de odd/tasks/orders-console.md): la vista
            // del día con todos los estados es la pantalla que el KDS NO usa,
            // así que exige el rol propio de mostrador. PEDIDOS es el rol
            // dedicado; OWNER/ADMIN conservan su acceso; los contratos del KDS
            // (orders/active, status, cancel) siguen abiertos a cualquier
            // usuario autenticado del negocio — gatearlos es otro slice.
            .requestMatchers(HttpMethod.GET, "/api/business/orders").hasAnyRole("PEDIDOS", "OWNER", "ADMIN")

            .requestMatchers(HttpMethod.POST, "/api/predictions/run").hasAnyRole("OWNER", "ADMIN")

	            .anyRequest().authenticated()
	        )

	        // CONTRATO DE ERRORES HTTP para toda la cadena: un anónimo (sin
	        // credenciales) recibe 401 del entry point; un AUTENTICADO sin el
	        // rol exigido sigue recibiendo 403 del AccessDeniedHandler (que
	        // queda por default, no se toca). ExceptionTranslationFilter manda
	        // al anónimo al entry point y al autenticado al handler, así que
	        // esta única configuración separa "no tenés sesión" de "no tenés
	        // privilegio". Se eligió HttpStatusEntryPoint (no
	        // BearerTokenAuthenticationEntryPoint) porque ésta vive en
	        // spring-security-oauth2-resource-server, que no está en el
	        // classpath y agregar la dependencia está fuera de alcance. Sin
	        // matchers por ruta: la regla es de TODA la cadena. Un intento
	        // anterior con defaultAuthenticationEntryPointFor(matcher)
	        // convirtió en 401 a TODO el sitio: el builder toma la primera
	        // entrada como default global cuando no hay default declarado.
	        .exceptionHandling(e -> e
	            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
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
