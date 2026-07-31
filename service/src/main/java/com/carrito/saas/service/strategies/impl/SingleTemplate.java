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
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

/**
 * Esta clase imprime un QR por pagina.
 */
@Service
@RequiredArgsConstructor
public class SingleTemplate implements PdfTemplateStrategy {
	private final IQrCodeService qrCodeService;

	@Override
	public QrTemplate getTemplate() {
		return QrTemplate.SINGLE;
	}

	@Override
	public byte[] generate(List<RestaurantTable> tables, QrPdfRequestDTO config) {
		  try {

	            ByteArrayOutputStream output =
	                    new ByteArrayOutputStream();

	            Document document =
	                    new Document(PageSize.A4);

	            PdfWriter.getInstance(
	                    document,
	                    output);

	            document.open();

	            Font titleFont =
	                    FontFactory.getFont(
	                            FontFactory.HELVETICA_BOLD,
	                            18);

	            Font normalFont =
	                    FontFactory.getFont(
	                            FontFactory.HELVETICA,
	                            12);

	            int qrSize =
	                    config.getQrSize() != null
	                    ? config.getQrSize()
	                    : 350;

	            for (RestaurantTable table : tables) {

	                document.add(
	                        new Paragraph(
	                                table.getTableName(),
	                                titleFont));

	                document.add(
	                        new Paragraph(
	                                "Mesa N° "
	                                        + table.getTableNumber(),
	                                normalFont));

	                document.add(
	                        new Paragraph(" "));

	                String url =
	                        "http://localhost:3030/"
	                                + table.getQrToken();

	                byte[] qr =
	                        qrCodeService.generateQr(
	                                url,
	                                qrSize);

	                Image image =
	                        Image.getInstance(qr);

	                image.scaleToFit(
	                        qrSize,
	                        qrSize);

	                image.setAlignment(
	                        Image.ALIGN_CENTER);

	                document.add(image);

	                if (Boolean.TRUE.equals(
	                        config.getShowUrl())) {

	                    document.add(
	                            new Paragraph(url));
	                }

	                document.newPage();
	            }

	            document.close();

	            return output.toByteArray();

	        } catch (Exception ex) {
	            throw new RuntimeException(ex);
	        }
	    }

}
