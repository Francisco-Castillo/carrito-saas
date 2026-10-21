package com.carrito.saas.service.impl;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;

import lombok.extern.slf4j.Slf4j;

/**
 * Almacenamiento de imágenes subidas (productos, combos, logo/banner del
 * negocio) EN POSTGRES, no en el filesystem. El demo corre en Render free,
 * donde el filesystem del contenedor (/tmp) se vacía en cada spin-down
 * (~15 min) y en cada redeploy — las URLs persistían en la base pero los
 * archivos desaparecían (imagen rota). Postgres es el único storage que el
 * plan gratuito persiste; 5 MB de imagen máxima caben sobrado en BYTEA.
 *
 * Contrato público SIN cambios: cada archivo vive bajo un UUID aleatorio
 * (el nombre NUNCA viene del cliente — elimina path traversal y pisotón) y
 * la URL persistida en {@code image_url} sigue siendo
 * {@code /api/images/{kind}/<uuid>.<ext>}, ahora servida por
 * {@code UploadedImageController} desde esta tabla.
 *
 * La tabla {@code uploaded_images} es JDBC pura (schema.sql), sin entidad
 * JPA — mismo criterio que las tablas de analytics. {@code kind} duplica el
 * segmento del path en la fila para que un UUID solo resuelva dentro de su
 * propio prefijo (un DELETE de producto no puede borrar el logo).
 */
@Service
@Slf4j
public class ImageStorageService {

	/** Content types aceptados y la extensión canónica de cada uno. */
	private static final java.util.Map<String, String> ALLOWED_TYPES = java.util.Map.of(
			"image/jpeg", ".jpg",
			"image/png", ".png",
			"image/webp", ".webp");

	/** Techo duro de archivo (5 MB): el mismo límite que fija multipart en application.yml. */
	static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

	/** Prefijos públicos por kind: la URL completa es prefix + uuid + extensión. */
	private static final String PRODUCTS_URL_PREFIX = "/api/images/products/";
	private static final String COMBOS_URL_PREFIX = "/api/images/combos/";
	private static final String BUSINESS_URL_PREFIX = "/api/images/business/";

	/** Los kinds válidos, en el orden en que los prefixes los recorren. */
	private enum Kind {
		PRODUCTS(PRODUCTS_URL_PREFIX, "products"),
		COMBOS(COMBOS_URL_PREFIX, "combos"),
		BUSINESS(BUSINESS_URL_PREFIX, "business");

		final String urlPrefix;
		final String dbKind;

		Kind(String urlPrefix, String dbKind) {
			this.urlPrefix = urlPrefix;
			this.dbKind = dbKind;
		}
	}

	/** Bytes servibles: lo que el controller devuelve con su Content-Type. */
	public record StoredImage(String contentType, byte[] data) {
	}

	private final JdbcTemplate jdbc;

	public ImageStorageService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Guarda el contenido de una imagen de producto y devuelve la URL pública
	 * relativa para persistir en la entidad.
	 *
	 * @throws BusinessException VALIDATION si el tipo o el tamaño no cumplen
	 */
	public String store(byte[] data, String contentType) {
		return storeAs(Kind.PRODUCTS, data, contentType);
	}

	public String storeCombo(byte[] data, String contentType) {
		return storeAs(Kind.COMBOS, data, contentType);
	}

	/**
	 * Guarda el contenido de una imagen del NEGOCIO (logo o banner) y devuelve
	 * la URL pública relativa para persistir en el perfil. Mismas reglas que
	 * {@link #store}: nombre UUID, tipo y tamaño validados.
	 */
	public String storeBusinessImage(byte[] data, String contentType) {
		return storeAs(Kind.BUSINESS, data, contentType);
	}

	private String storeAs(Kind kind, byte[] data, String contentType) {

		String extension = extensionOf(contentType, data.length);
		UUID id = UUID.randomUUID();

		try {
			jdbc.update("INSERT INTO uploaded_images (id, kind, content_type, data) VALUES (?, ?, ?, ?)",
					id, kind.dbKind, contentType, data);
		} catch (org.springframework.dao.DataAccessException e) {
			log.error("No se pudo guardar la imagen de {}", kind.dbKind, e);
			throw new BusinessException("No se pudo guardar la imagen", ErrorType.INTERNAL_ERROR);
		}

		return kind.urlPrefix + id + extension;
	}

	/**
	 * Resuelve una URL previamente devuelta por {@link #store},
	 * {@link #storeCombo} o {@link #storeBusinessImage} a sus bytes. URLs
	 * ajenas, vacías, con kind inválido o con id inexistente devuelven
	 * {@code Optional#empty()} — nunca excepción: servir es best-effort.
	 */
	public Optional<StoredImage> loadByUrl(String url) {

		ParsedImage parsed = parseUrl(url);
		if (parsed == null) {
			return Optional.empty();
		}

		try {
			return jdbc.query(
					"SELECT content_type, data FROM uploaded_images WHERE id = ? AND kind = ?",
					rs -> {
						if (!rs.next()) {
							return Optional.empty();
						}
						return Optional.of(new StoredImage(rs.getString(1), rs.getBytes(2)));
					},
					parsed.id(), parsed.kind().dbKind);
		} catch (org.springframework.dao.DataAccessException e) {
			log.warn("No se pudo leer la imagen {}: {}", url, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Borra la imagen apuntada por una URL previamente devuelta por
	 * {@link #store} o {@link #storeBusinessImage}. Un URL ajeno, vacío o fuera
	 * de los prefijos gestionados se ignora en silencio: borrar nunca rompe
	 * el flujo de guardado.
	 */
	public void deleteByUrl(String url) {
		deleteParsed(parseUrl(url));
	}

	public void deleteComboByUrl(String url) {
		deleteParsed(parseUrl(url));
	}

	private void deleteParsed(ParsedImage parsed) {

		if (parsed == null) {
			return;
		}

		try {
			jdbc.update("DELETE FROM uploaded_images WHERE id = ? AND kind = ?",
					parsed.id(), parsed.kind().dbKind);
		} catch (org.springframework.dao.DataAccessException e) {
			// Queda un huérfano en la base: no bloquea la operación.
			log.warn("No se pudo borrar la imagen {}: {}", parsed.id(), e.getMessage());
		}
	}

	/** Fila de URL ya descompuesta, lista para consultar la base. */
	private record ParsedImage(UUID id, Kind kind) {
	}

	/**
	 * Descompone {@code /api/images/{kind}/<uuid>.<ext>} en (uuid, kind).
	 * Devuelve null para cualquier URL que no calce exactamente el contrato:
	 * prefijo desconocido, filename sin UUID parseable, path tricks.
	 */
	private ParsedImage parseUrl(String url) {

		if (url == null || url.isBlank()) {
			return null;
		}

		for (Kind kind : Kind.values()) {
			if (!url.startsWith(kind.urlPrefix)) {
				continue;
			}
			String fileName = url.substring(kind.urlPrefix.length());
			// Sin '/' residual ni "..": el filename es UN segmento plano.
			if (fileName.isEmpty() || fileName.contains("/")) {
				return null;
			}
			String baseName = stripExtension(fileName);
			try {
				return new ParsedImage(UUID.fromString(baseName), kind);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		return null;
	}

	private String stripExtension(String fileName) {

		int dot = fileName.lastIndexOf('.');
		return dot <= 0 ? fileName : fileName.substring(0, dot);
	}

	private String extensionOf(String contentType, long size) {

		if (size <= 0) {
			throw new BusinessException("El archivo de imagen está vacío", ErrorType.VALIDATION);
		}
		if (size > MAX_SIZE_BYTES) {
			throw new BusinessException("La imagen supera el máximo de 5 MB", ErrorType.VALIDATION);
		}
		String extension = ALLOWED_TYPES.get(contentType);
		if (extension == null) {
			throw new BusinessException("Formato de imagen no soportado: usá JPG, PNG o WebP",
					ErrorType.VALIDATION);
		}
		return extension;
	}
}
