package com.carrito.saas.security;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.carrito.saas.tenant.BranchContext;
import com.carrito.saas.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtFilter extends OncePerRequestFilter {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtFilter.class);

	private final JwtUtil jwtUtil;
	private final UserDetailsService userDetailsService;

	public JwtFilter(JwtUtil jwtUtil, UserDetailsService userDetailsService) {
		super();
		this.jwtUtil = jwtUtil;
		this.userDetailsService = userDetailsService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String path = request.getServletPath();

		// Ignorar endpoints públicos y Actuator
		if (path.startsWith("/actuator") || path.startsWith("/api/auth")) {
			filterChain.doFilter(request, response);
			return;
		}

		String header = request.getHeader("Authorization");

		if (header == null || !header.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}

		try {

			String token = header.substring(7);
			String username = jwtUtil.extractUsername(token);
			Long businessId = jwtUtil.extractBusinessId(token);
			
			/*
			String role = jwtUtil.extractRole(token);
			String businessSlug = jwtUtil.extractBusinessSlug(token);*/
			
			// Un token SIN businessId es el token del operador de plataforma
			// (SecurityService.platformLogin, odd/tasks/plataforma-altas.md):
			// antes se descartaba con "JWT sin businessId" y el operador caía a
			// anónimo con 401. Autentica igual, pero SIN fijar contexto de
			// tenant: el scoping de negocio es por parámetro explícito y el
			// operador no escribe filas de negocio. Solo el login de plataforma
			// emite tokens sin claim, así que ningún token de negocio cambia.

			if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

				var userDetails = userDetailsService.loadUserByUsername(username);

				if (jwtUtil.validateToken(token, userDetails)) {

					UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails,
							token, userDetails.getAuthorities());

					SecurityContextHolder.getContext().setAuthentication(auth);

					if (businessId != null) {
						// CONTEXTO MULTI-TENANT (solo tokens de negocio)
						TenantContext.set(businessId);

						// CONTEXTO DE SUCURSAL (multi-sucursal, T3): el claim
						// branchId fija la sucursal activa del panel. Null en
						// tokens viejos: la resolución con fallback (una sola
						// activa, o 400 pidiendo selección) la hace
						// SecurityService.getCurrentBranchId(), no el filtro.
						Long branchId = jwtUtil.extractBranchId(token);
						if (branchId != null) {
							BranchContext.set(branchId);
						}
					}

					// LOGS (MDC)
					MDC.put("businessId", businessId == null ? "platform" : String.valueOf(businessId));
					MDC.put("userId", username);
					
					//  BONUS PRO logs
					/*
		            MDC.put("role", role);
		            MDC.put("businessSlug", businessSlug);*/
				}
			}

		} catch (Exception e) {
			// Un fallo de autenticación silenciado responde 401 sin ningún
			// rastro de la causa (token inválido, DB caída, datos duplicados):
			// sin este log se vuelve indebuggable. WARN, no stack completo.
			log.warn("Fallo autenticando el JWT: {}", e.getMessage());
			SecurityContextHolder.clearContext();
		}

		try {
	        filterChain.doFilter(request, response);
	    } finally {
	        //  LIMPIEZA (CRÍTICO)
	        TenantContext.clear();
	        BranchContext.clear();
	        MDC.clear();
	    }
	}
}
