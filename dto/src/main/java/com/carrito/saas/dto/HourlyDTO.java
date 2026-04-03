package com.carrito.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HourlyDTO {
	private Integer hour;
    private Long orders;

}
