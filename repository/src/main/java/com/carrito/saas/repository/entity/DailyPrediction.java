package com.carrito.saas.repository.entity;

import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;

@Entity
@Table(name = "daily_predictions")
@Data
@AllArgsConstructor
public class DailyPrediction {
	
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long businessId;

    private LocalDate date;

    private Integer predictedOrders;

    private Integer peakHour;

    private Long topProductId;

}
