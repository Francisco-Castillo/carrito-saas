package com.carrito.saas.dto;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class CancellationReasonDTO {

	private Long id;

	private String code;

	private String description;

	private Boolean active;

	private LocalDateTime createdAt;

}
