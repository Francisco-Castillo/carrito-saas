package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.repository.entity.RestaurantTable;

public interface IQrPdfService {
	
	byte[] generateTablePdf(
            RestaurantTable table,
            QrPdfRequestDTO config);

    byte[] generateTablesPdf(
            List<RestaurantTable> tables,
            QrPdfRequestDTO config);

}
