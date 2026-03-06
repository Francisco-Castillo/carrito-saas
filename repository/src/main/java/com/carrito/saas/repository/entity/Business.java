package com.carrito.saas.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "businesses")
public class Business {

    @Id
    private Long id;

    private String name;

    private String slug;

    private String phone;

}