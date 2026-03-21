package com.carrito.saas.repository.entity;

import java.math.BigDecimal;

import com.carrito.saas.repository.enums.UnitMeasure;

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

}

