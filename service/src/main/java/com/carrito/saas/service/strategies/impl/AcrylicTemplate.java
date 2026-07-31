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
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AcrylicTemplate implements PdfTemplateStrategy {
	private final IQrCodeService qrCodeService;

	@Override
	public QrTemplate getTemplate() {
		return QrTemplate.ACRYLIC;
	}

	@Override
	public byte[] generate(List<RestaurantTable> tables, QrPdfRequestDTO config) {

		try {

			ByteArrayOutputStream output = new ByteArrayOutputStream();

			Document document = new Document(PageSize.A4);

			PdfWriter.getInstance(document, output);

			document.open();

			Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 28);

			Font subtitle = FontFactory.getFont(FontFactory.HELVETICA, 18);

			for (RestaurantTable table : tables) {

				Paragraph p = new Paragraph(table.getTableName(), title);

				p.setAlignment(Element.ALIGN_CENTER);

				document.add(p);

				document.add(new Paragraph(" "));

				String url = "http://localhost:3030/" + table.getQrToken();

				byte[] qr = qrCodeService.generateQr(url, 500);

				Image image = Image.getInstance(qr);

				image.scaleToFit(350, 350);

				image.setAlignment(Image.ALIGN_CENTER);

				document.add(image);

				Paragraph txt = new Paragraph("Escanee para ver el menú", subtitle);

				txt.setAlignment(Element.ALIGN_CENTER);

				document.add(txt);

				document.newPage();
			}

			document.close();

			return output.toByteArray();

		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}
