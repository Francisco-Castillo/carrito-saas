package com.carrito.saas.repository.entity;

import java.time.LocalDateTime;

import com.carrito.saas.repository.enums.TableStatus;

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
import lombok.Data;

@Entity
@Table(name = "restaurant_table")
@Data
public class RestaurantTable {
	
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
	

	@Column(name = "table_number")
    private Integer tableNumber;

	@Column(name = "table_name")
    private String tableName;

    @Column(unique = true, name = "qr_token")
    private String qrToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TableStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

}
