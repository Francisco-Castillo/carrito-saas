package com.carrito.saas.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

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
	protected void doFilterInternal(HttpServletRequest request,
	                                HttpServletResponse response,
	                                FilterChain filterChain)
	        throws ServletException, IOException {

	    String path = request.getServletPath();

	    if (path.startsWith("/api/auth")) {
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

	        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

	            var userDetails = userDetailsService.loadUserByUsername(username);

	            if (jwtUtil.validateToken(token, userDetails)) {

	                UsernamePasswordAuthenticationToken auth =
	                        new UsernamePasswordAuthenticationToken(
	                                userDetails,
	                                null,
	                                userDetails.getAuthorities()
	                        );

	                SecurityContextHolder.getContext().setAuthentication(auth);
	            }
	        }

	    } catch (Exception e) {
	        SecurityContextHolder.clearContext();
	    }

	    filterChain.doFilter(request, response);
	}
}
