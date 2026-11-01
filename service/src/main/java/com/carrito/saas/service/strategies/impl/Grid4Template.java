package com.carrito.saas.service.strategies.impl;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.QrTemplate;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.strategies.interfaces.PdfTemplateStrategy;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import lombok.RequiredArgsConstructor;

/**
 * Plantilla {@code GRID_4X4}: 16 QRs por hoja A4 (4 columnas × 4 filas), para
 * pegar muchos códigos de una sola impresión.
 *
 * <p>{@code QrTemplate.GRID_4X4} existía en el enum pero no tenía estrategia:
 * {@link PdfTemplateFactory} tiraba "Unsupported template" y
 * {@code GET /api/tables/qr/pdf?template=GRID_4X4} respondía 500. Esta clase
 * es esa estrategia que faltaba.</p>
 *
 * <p>Dos decisiones de impresión que la diferencian de la grilla 3×3:</p>
 * <ul>
 * <li><b>Tamaño</b>: A4 tiene 595pt de ancho y 72pt de márgenes por defecto
 * (523pt útiles). Con 4 columnas, celda = 130pt; la imagen se imprime a 110pt
 * con 6pt de padding, así la tabla entra sin encogerse ni empujar la página.
 * El QR se genera a 4× ese tamaño en píxeles y se escala: 110px de mapa de
 * bits sobre 110pt es ~72dpi y sale borroso, 440px es ~288dpi y se escanea
 * igual de bien impreso.</li>
 * <li><b>Filas incompletas</b>: {@link PdfPTable} sólo dibuja una fila cuando
 * está completa, así que un grupo final de 1 a 3 mesas se perdía y una hoja
 * con menos de 4 mesas no generaba ni una página (lección de
 * {@link Grid3Template}, fijada en {@code Grid3RowRenderingTests}).
 * {@code completeRow()} rellena esa fila con celdas vacías.</li>
 * </ul>
 *
 * <p>El nombre de la mesa va arriba del QR en 10pt: en una celda de 130pt un
 * texto más grande parte el nombre en varias líneas y desalinea la grilla.</p>
 */
@Service
@RequiredArgsConstructor
public class Grid4Template implements PdfTemplateStrategy {

	/** Columnas (y filas) de la grilla: 4 × 4 = 16 QRs por hoja A4. */
	private static final int COLUMNS = 4;

	/** Lado del QR impreso, en puntos. Ver el javadoc de la clase. */
	private static final int QR_PRINT_SIZE = 110;

	/** Píxeles por punto al generar el QR: 4× para que la impresión sea nítida. */
	private static final int QR_RENDER_FACTOR = 4;

	/** Aire alrededor de cada celda, en puntos. */
	private static final float CELL_PADDING = 6f;

	/** Debajo de esto el QR impreso no se escanea de forma confiable. */
	private static final int MIN_QR_PRINT_SIZE = 40;

	/** Cuerpo de la URL impresa cuando el caller pide showUrl. */
	private static final int URL_FONT_SIZE = 6;

	/** Una URL no tiene espacios: se corta a mano para que no se salga de la celda. */
	private static final int URL_LINE_LENGTH = 34;

	private final IQrCodeService qrCodeService;
	private final IMenuUrlBuilder menuUrlBuilder;

	@Override
	public QrTemplate getTemplate() {
		return QrTemplate.GRID_4X4;
	}

	@Override
	public byte[] generate(List<RestaurantTable> tables, QrPdfRequestDTO config) {

		if (tables == null || tables.isEmpty()) {
			throw new IllegalArgumentException("No tables found.");
		}

		// El DTO es contrato, no adorno: quien pide otro tamaño o el texto de la
		// URL tiene que verlo en el PDF. El tamaño se acota a lo que entra en la
		// celda de una grilla 4x4, que es lo único que garantiza 16 códigos
		// legibles por hoja.
		int printSize = resolvePrintSize(config);
		boolean showTableName = !Boolean.FALSE.equals(config.getShowTableName());
		boolean showUrl = Boolean.TRUE.equals(config.getShowUrl());

		try {

			ByteArrayOutputStream output = new ByteArrayOutputStream();

			Document document = new Document(PageSize.A4);

			PdfWriter.getInstance(document, output);

			document.open();

			PdfPTable grid = new PdfPTable(COLUMNS);

			grid.setWidthPercentage(100);

			Font nameFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

			for (RestaurantTable table : tables) {

				String menuUrl = menuUrlBuilder.buildTableMenuUrl(table.getBusiness().getSlug(), table.getId());

				byte[] qr = qrCodeService.generateQr(menuUrl, printSize * QR_RENDER_FACTOR);

				if (qr == null || qr.length == 0) {
					throw new IllegalStateException("QR generation failed.");
				}

				PdfPCell cell = new PdfPCell();

				cell.setPadding(CELL_PADDING);

				cell.setHorizontalAlignment(Element.ALIGN_CENTER);

				if (showTableName) {

					Paragraph name = new Paragraph(table.getTableName(), nameFont);

					name.setAlignment(Element.ALIGN_CENTER);

					cell.addElement(name);
				}

				Image image = Image.getInstance(qr);

				image.scaleToFit(printSize, printSize);

				cell.addElement(image);

				if (showUrl) {
					cell.addElement(urlParagraph(menuUrl));
				}

				grid.addCell(cell);
			}

			// La última fila se completa con celdas vacías: sin esto las mesas
			// sobrantes no se imprimen (Grid3RowRenderingTests).
			grid.completeRow();

			document.add(grid);

			document.close();

			return output.toByteArray();

		} catch (Exception ex) {

			throw new RuntimeException("Error generating GRID_4X4 PDF", ex);
		}
	}

	/** Tamaño impreso pedido por el caller, acotado a lo que entra en la celda. */
	private static int resolvePrintSize(QrPdfRequestDTO config) {

		Integer requested = config == null ? null : config.getQrSize();

		if (requested == null || requested < MIN_QR_PRINT_SIZE || requested > QR_PRINT_SIZE) {
			return QR_PRINT_SIZE;
		}

		return requested;
	}

	/**
	 * URL del QR como texto, cortada cada {@value #URL_LINE_LENGTH} caracteres:
	 * OpenPDF no parte una URL sin espacios, así que sin los cortes el texto se
	 * sale de la celda.
	 */
	private static Paragraph urlParagraph(String url) {

		StringBuilder wrapped = new StringBuilder();

		for (int index = 0; index < url.length(); index++) {

			if (index > 0 && index % URL_LINE_LENGTH == 0) {
				wrapped.append('\n');
			}

			wrapped.append(url.charAt(index));
		}

		Paragraph paragraph = new Paragraph(wrapped.toString(),
				FontFactory.getFont(FontFactory.HELVETICA, URL_FONT_SIZE));

		paragraph.setAlignment(Element.ALIGN_CENTER);

		return paragraph;
	}
}
