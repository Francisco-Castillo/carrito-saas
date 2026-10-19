package com.carrito.saas.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;

import lombok.extern.slf4j.Slf4j;

/**
 * Almacenamiento de imágenes de producto en el sistema de archivos local.
 * Cada archivo vive en {@code <app.uploads.dir>/products/} con un nombre
 * aleatorio (UUID): el nombre NUNCA viene del cliente, lo que elimina de
 * raíz el path traversal y el pisotón de archivos existentes. La URL pública
 * que se persiste en {@code product.image_url} es {@code /api/images/...},
 * servida por un resource handler en el módulo api (ver
 * {@code UploadedImagesConfig}).
 */
@Service
@Slf4j
public class ImageStorageService {

	/** Content types aceptados y la extensión canónica de cada uno. */
	private static final Map<String, String> ALLOWED_TYPES = Map.of(
			"image/jpeg", ".jpg",
			"image/png", ".png",
			"image/webp", ".webp");

	/** Techo duro de archivo (5 MB): el mismo límite que fija multipart en application.yml. */
	static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

	private final Path productsDir;

	public ImageStorageService(@Value("${app.uploads.dir:./uploads}") String uploadsDir) {
		this.productsDir = Path.of(uploadsDir, "products").toAbsolutePath().normalize();
	}

	/**
	 * Guarda el contenido de una imagen de producto y devuelve la URL pública
	 * relativa para persistir en la entidad.
	 *
	 * @throws BusinessException VALIDATION si el tipo o el tamaño no cumplen;
	 *                          INTERNAL_ERROR si falla la escritura en disco.
	 */
	public String store(byte[] data, String contentType) {

		String extension = extensionOf(contentType, data.length);

		try {
			Files.createDirectories(productsDir);
			Path target = productsDir.resolve(UUID.randomUUID() + extension);
			Files.write(target, data);
			log.info("Imagen de producto guardada: {}", target);
			return "/api/images/products/" + target.getFileName();
		} catch (IOException e) {
			log.error("No se pudo guardar la imagen de producto", e);
			throw new BusinessException("No se pudo guardar la imagen", ErrorType.INTERNAL_ERROR);
		}
	}

	/**
	 * Borra la imagen apuntada por una URL previamente devuelta por
	 * {@link #store}. Un URL ajeno, vacío o fuera de productsDir se ignora
	 * en silencio: borrar nunca rompe el flujo de guardado.
	 */
	public void deleteByUrl(String url) {

		if (url == null || url.isBlank() || !url.startsWith("/api/images/products/")) {
			return;
		}

		String fileName = Path.of(url.substring("/api/images/products/".length())).getFileName().toString();
		try {
			Files.deleteIfExists(productsDir.resolve(fileName).normalize());
		} catch (IOException e) {
			// Queda un huérfano en disco: no bloquea la operación del producto.
			log.warn("No se pudo borrar la imagen {}: {}", url, e.getMessage());
		}
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
