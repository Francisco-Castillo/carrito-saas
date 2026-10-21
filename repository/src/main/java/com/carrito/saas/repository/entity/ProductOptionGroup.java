package com.carrito.saas.repository.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * Grupo de opciones de un producto (ej: "Tipo de pan", "Tamaño").
 *
 * Definición por producto (odd/tasks/product-options.md S3): sin biblioteca
 * compartida entre productos.
 *
 * Reglas de selección (S1):
 * - required=true  → el cliente DEBE elegir antes de agregar al carrito.
 * - multiple=false → elige exactamente 1 opción (radio).
 * - multiple=true  → elige entre minSelections y maxSelections opciones
 *   (checkboxes). maxSelections null → sin tope.
 */
@Getter
@Setter
@Entity
@Table(name = "product_option_groups")
public class ProductOptionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean required;

    @Column(nullable = false)
    private boolean multiple;

    private Integer minSelections;

    private Integer maxSelections;

    @OneToMany(mappedBy = "optionGroup", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderColumn(name = "position")
    private List<ProductOption> options = new ArrayList<>();
}
