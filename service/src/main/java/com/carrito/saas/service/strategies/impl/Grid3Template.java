package com.carrito.saas.service.strategies.impl;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.QrTemplate;
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.strategies.interfaces.PdfTemplateStrategy;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class Grid3Template implements PdfTemplateStrategy {

	private final IQrCodeService qrCodeService;

	@Override
	public QrTemplate getTemplate() {
		return QrTemplate.GRID_3X3;
	}

	@Override
	public byte[] generate(
	        List<RestaurantTable> tables,
	        QrPdfRequestDTO config) {

	    if (tables == null || tables.isEmpty()) {
	        throw new IllegalArgumentException(
	                "No tables found.");
	    }

	    try {

	        ByteArrayOutputStream output =
	                new ByteArrayOutputStream();

	        Document document =
	                new Document(PageSize.A4);

	        PdfWriter.getInstance(document, output);

	        document.open();

	        PdfPTable pdfTable =
	                new PdfPTable(3);

	        pdfTable.setWidthPercentage(100);

	        int qrSize = 180;

	        for (RestaurantTable table : tables) {

	            byte[] qr =
	                    qrCodeService.generateQr(
	                            "http://localhost:3030/"
	                                    + table.getQrToken(),
	                            qrSize);

	            if (qr == null || qr.length == 0) {
	                throw new IllegalStateException(
	                        "QR generation failed.");
	            }

	            PdfPCell cell =
	                    new PdfPCell();

	            cell.setPadding(10);

	            cell.setHorizontalAlignment(
	                    Element.ALIGN_CENTER);

	            cell.addElement(
	                    new Paragraph(
	                            table.getTableName()));

	            Image image =
	                    Image.getInstance(qr);

	            image.scaleToFit(
	                    qrSize,
	                    qrSize);

	            cell.addElement(image);

	            pdfTable.addCell(cell);
	        }

	        document.add(pdfTable);

	        document.close();

	        return output.toByteArray();

	    } catch (Exception ex) {

	        throw new RuntimeException(
	                "Error generating GRID_3X3 PDF",
	                ex);
	    }
	}


}