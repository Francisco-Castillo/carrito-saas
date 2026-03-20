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

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    private String name;

}