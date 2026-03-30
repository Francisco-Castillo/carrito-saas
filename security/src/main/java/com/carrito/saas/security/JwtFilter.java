package com.carrito.saas.security;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.carrito.saas.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtFilter extends OncePerRequestFilter {

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
			
			if (businessId == null) {
			    throw new SecurityException("JWT sin businessId");
			}

			if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

				var userDetails = userDetailsService.loadUserByUsername(username);

				if (jwtUtil.validateToken(token, userDetails)) {

					UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetails,
							token, userDetails.getAuthorities());

					SecurityContextHolder.getContext().setAuthentication(auth);

					// CONTEXTO MULTI-TENANT
					TenantContext.set(businessId);

					// LOGS (MDC)
					MDC.put("businessId", String.valueOf(businessId));
					MDC.put("userId", username);
					
					//  BONUS PRO logs
					/*
		            MDC.put("role", role);
		            MDC.put("businessSlug", businessSlug);*/
				}
			}

		} catch (Exception e) {
			SecurityContextHolder.clearContext();
		}

		try {
	        filterChain.doFilter(request, response);
	    } finally {
	        //  LIMPIEZA (CRÍTICO)
	        TenantContext.clear();
	        MDC.clear();
	    }
	}
}
