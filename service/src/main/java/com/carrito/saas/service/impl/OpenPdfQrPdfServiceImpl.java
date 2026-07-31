package com.carrito.saas.service.impl;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.interfaces.IQrPdfService;
import com.carrito.saas.service.strategies.impl.PdfTemplateFactory;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OpenPdfQrPdfServiceImpl implements IQrPdfService {

	private final IQrCodeService qrCodeService;

	// private final QrProperties qrProperties;

	private final PdfTemplateFactory factory;

	@Override
	public byte[] generateTablePdf(RestaurantTable table, QrPdfRequestDTO config) {

		return factory.get(config.getTemplate()).generate(List.of(table), config);
	}

	@Override
	public byte[] generateTablesPdf(List<RestaurantTable> tables, QrPdfRequestDTO config) {

		return factory.get(config.getTemplate()).generate(tables, config);
	}

}
