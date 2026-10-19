package com.carrito.saas.dto;

import com.carrito.saas.repository.enums.TableStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateTableRequestDTO {
	
	@NotNull(message = "Table number is required")
    @Positive(message = "Table number must be greater than zero")
    private Integer tableNumber;

    @NotBlank(message = "Table name is required")
    @Size(max = 100, message = "Table name cannot exceed 100 characters")
    private String tableName;

    @NotNull(message = "Table status is required")
    private TableStatus status;

}
