package com.carrito.saas.repository.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category {

    @Id
    private Long id;

    @Column(name = "business_id")
    private Long businessId;

    private String name;

}