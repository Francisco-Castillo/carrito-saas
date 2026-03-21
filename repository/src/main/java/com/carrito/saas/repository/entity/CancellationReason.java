package com.carrito.saas.repository.entity;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "cancellation_reasons")
@Data
public class CancellationReason {
	
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // SERIAL / BIGSERIAL
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    // Relación inversa (opcional pero útil para analytics)
    @OneToMany(mappedBy = "cancellationReason")
    private List<Order> orders;

}
