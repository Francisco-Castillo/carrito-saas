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
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "order_items")
public class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne
	@JoinColumn(name = "order_id")
	private Order order;

	@Column(name = "product_id")
	private Long productId;

	@Column(name = "product_name")
	private String productName;

	private BigDecimal price;
	
	/**
	 * Costo del producto al momento de la venta
	 */
	private BigDecimal cost;

	private Integer quantity;

	private BigDecimal subtotal;

	// referencia al combo (si aplica)
	@ManyToOne
	@JoinColumn(name = "combo_id")
	private Combo combo;
	
	@Column(name = "is_combo_root")
	private Boolean comboRoot;

}
