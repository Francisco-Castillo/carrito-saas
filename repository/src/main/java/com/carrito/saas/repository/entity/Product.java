package com.carrito.saas.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "products")
public class Product {

    @Id
    private Long id;

    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "category_id")
    private Long categoryId;

    private String name;

    private String description;

    private Integer price;

}