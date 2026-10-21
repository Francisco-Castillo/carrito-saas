package com.carrito.saas.repository.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * Opción dentro de un grupo (ej: "Pan integral" en "Tipo de pan").
 *
 * priceDelta (S2): recargo que la opción suma al precio del producto cuando
 * el cliente la elige. Null o cero → la opción no cambia el precio.
 */
@Getter
@Setter
@Entity
@Table(name = "product_options")
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private ProductOptionGroup optionGroup;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_delta", nullable = false)
    private BigDecimal priceDelta = BigDecimal.ZERO;

    /** Orden de mostrado dentro del grupo (el que cargó el dueño). */
    private Integer position;
}
