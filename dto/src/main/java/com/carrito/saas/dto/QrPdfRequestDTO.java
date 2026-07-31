package com.carrito.saas.dto;

import com.carrito.saas.repository.enums.QrTemplate;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QrPdfRequestDTO {
	
	@NotNull
    private QrTemplate template;

    private Boolean showLogo = true;

    private Boolean showUrl = false;

    private Boolean showTableName = true;

    private Integer qrSize;

}
