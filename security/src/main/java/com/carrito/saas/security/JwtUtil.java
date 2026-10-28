package com.carrito.saas.security;

import java.security.Key;
import java.util.Date;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtUtil {

	private final String SECRET = "super_secret_key_for_jwt_token_super_secret_key_for_jwt";

	private Key getSigningKey() {

		return Keys.hmacShaKeyFor(SECRET.getBytes());
	}

	public String generateToken(String username, Long businessId) {

		return generateToken(username, businessId, null);
	}

	/**
	 * Token de negocio con sucursal activa (multi-sucursal, T3): el claim
	 * opcional {@code branchId} fija la sucursal del panel. Con branchId null
	 * el claim no viaja y el token es idéntico al legacy.
	 */
	public String generateToken(String username, Long businessId, Long branchId) {

		var builder = Jwts.builder().setSubject(username).claim("businessId", businessId);

		if (branchId != null) {
			builder.claim("branchId", branchId);
		}

		return builder.setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis() + 86400000))
				.signWith(getSigningKey()).compact();
	}

	public boolean validateToken(String token, UserDetails userDetails) {

		final String username = extractUsername(token);

		return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
	}

	public boolean isTokenExpired(String token) {

		return extractExpiration(token).before(new Date());
	}

	public String extractUsername(String token) {

		return extractAllClaims(token).getSubject();
	}

	public Date extractExpiration(String token) {

		return extractAllClaims(token).getExpiration();
	}

	public Claims extractAllClaims(String token) {

		return Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token).getBody();
	}
	
	public Long extractBusinessId(String token) {
	    return extractAllClaims(token).get("businessId", Long.class);
	}

	public Long extractBranchId(String token) {
	    return extractAllClaims(token).get("branchId", Long.class);
	}
	
	public long getExpirationMillis() {
	    return 86400000; // 24h
	}
}
