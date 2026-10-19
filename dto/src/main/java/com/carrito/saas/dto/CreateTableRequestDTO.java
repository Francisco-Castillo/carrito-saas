package com.carrito.saas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateTableRequestDTO {

	@NotNull
	@Positive
	private Integer tableNumber;

	@NotBlank
	@Size(max = 100)
	private String tableName;

}
