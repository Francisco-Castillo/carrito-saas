package com.carrito.saas.service.strategies.interfaces;

import java.util.List;

import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.QrTemplate;

public interface PdfTemplateStrategy {
	
	QrTemplate getTemplate();
	
	 byte[] generate(
	            List<RestaurantTable> tables,
	            QrPdfRequestDTO config);

}
