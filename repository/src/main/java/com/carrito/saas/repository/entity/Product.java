package com.carrito.saas.repository.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.carrito.saas.repository.enums.UnitMeasure;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
   
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;
    
    /**
     * Costo real del producto (no visible al cliente)
     */
    @Column(nullable = false)
    private BigDecimal cost;
    
    private boolean active=true;
    
    /**
     * null → stock infinito (bebidas por ejemplo)
0 → sin stock
>0 → stock limitado
     */
    private Integer stock;
    
    @Column(name = "image_url")
    private String imageUrl;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "unit_measure")
    private UnitMeasure unitMeasure;

    /**
     * Grupos de opciones del producto (odd/tasks/product-options.md S1-S3).
     * Cascade ALL + orphanRemoval: el ciclo de vida de los grupos es el del
     * producto (crear/actualizar reemplaza, borrar producto borra grupos).
     * El orden de mostrado es el de creación (id), igual que el dueño cargó.
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ProductOptionGroup> optionGroups = new ArrayList<>();

}

