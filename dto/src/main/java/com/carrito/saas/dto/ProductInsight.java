package com.carrito.saas.dto;

public enum ProductInsight {
	
	CRITICAL,     // ↓ trend y ↓ predicción
    OPPORTUNITY,  // ↓ trend y ↑ predicción
    BOOST,        // ↑ trend y ↑ predicción
    UNSTABLE,     // ↑ trend y ↓ predicción
    NEUTRAL       // fallback

}
