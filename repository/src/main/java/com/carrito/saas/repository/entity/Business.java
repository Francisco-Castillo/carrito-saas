package com.carrito.saas.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Entity
@Table(name = "businesses")
public class Business {

    @Id
    private Long id;

    private String name;

    /**
     * Public identity of the business: the menu is addressed by it
     * ({@code /menu/index.html?restaurant=<slug>}, {@code /api/orders/menu/{slug}})
     * and {@code BusinessRepository.findBySlug} assumes at most one row per
     * value, so it is UNIQUE and NOT NULL.
     *
     * <p>Runtime NOT NULL enforcement relies on
     * {@code spring.jpa.properties.hibernate.check_nullability: true} in
     * {@code application.yml}: Hibernate skips its own nullability check when a
     * Bean Validation provider is on the classpath, and this module has no
     * {@code jakarta.validation} dependency to declare {@code @NotNull}.</p>
     */
    @Column(unique = true, nullable = false)
    private String slug;

    private String phone;

}