package com.carrito.saas.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GenerateTablesRequestDTO {

	@NotNull
	@Min(1)
	@Max(300)
	private Integer quantity;

}
