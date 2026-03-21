package com.carrito.saas.repository.entity;

import java.util.List;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // <- Esto hace que la DB genere el ID
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;

    private String name;
    
    private Integer orden;

    private Boolean active = true;
    
    @OneToMany(mappedBy = "category")
    private List<Product> products;

}