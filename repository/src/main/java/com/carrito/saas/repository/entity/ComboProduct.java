package com.carrito.saas.repository.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Esta entidad representa la "Receta" de un combo o promo. Es decir los
 * productos que componen el combo.
 */
@Entity
@Table(name = "combo_products")
@Data
public class ComboProduct {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// Combo al que pertenece
	@ManyToOne
	@JoinColumn(name = "combo_id")
	private Combo combo;

	// Producto que lo compone
	@ManyToOne
	@JoinColumn(name = "producto_id")
	private Product product;

    @Column(nullable = false)
	private BigDecimal quantity;

}
