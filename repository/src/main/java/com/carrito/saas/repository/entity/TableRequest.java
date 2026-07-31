package com.carrito.saas.repository.entity;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "table_request")
public class TableRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY) // <- Esto hace que la DB genere el ID
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "business_id", nullable = false)
	private Business business;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "table_id", nullable = false)
	private RestaurantTable restaurantTable;

	@Column(name = "request_type")
	private String requestType;

	@Column(name = "status")
	private String status;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "attended_at")
	private LocalDateTime attendedAt;
}

/*
 * CREATE TABLE table_request ( id BIGSERIAL PRIMARY KEY,
 * 
 * restaurant_id BIGINT NOT NULL,
 * 
 * table_id BIGINT NOT NULL,
 * 
 * VARCHAR(20) NOT NULL,
 * 
 * VARCHAR(20) NOT NULL,
 * 
 * TIMESTAMP NOT NULL,
 * 
 * attended_at TIMESTAMP );
 */