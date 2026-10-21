package com.carrito.saas.api;

import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.service.impl.ImageStorageService;

/**
 * Serving público de las imágenes subidas (productos, combos, logo/banner):
 * los bytes viven en la tabla {@code uploaded_images} (Postgres) — el
 * filesystem del contenedor es efímero en Render free y las URLs que
 * persisten en las entidades sobreviven a spin-downs y redeploys — y se
 * sirven en {@code /api/images/{kind}/{uuid}.{ext}} sin token, igual que
 * {@code /dev-media/**}: la carta digital pública necesita mostrarlas.
 *
 * Reemplaza al resource handler estático de {@code UploadedImagesConfig}
 * (borrado): mismo contrato de URL, misma apertura anónima, pero la
 * resolución es uuid + kind contra {@link ImageStorageService}, que valida
 * forma y kind (el filename nunca toca un path real — no hay traversal
 * posible). Los UUID no se reescriben nunca, así que el cache del browser
 * puede ser agresivo.
 */
@RestController
public class UploadedImageController {

	private final ImageStorageService imageStorageService;

	public UploadedImageController(ImageStorageService imageStorageService) {
		this.imageStorageService = imageStorageService;
	}

	@GetMapping("/api/images/{kind}/{fileName}")
	public ResponseEntity<byte[]> serve(@PathVariable String kind, @PathVariable String fileName) {

		// Solo los tres kinds del contrato; cualquier otro segmento es 404
		// (el loadByUrl ya filtra por prefijo, esto da el mismo resultado
		// sin consultas inútiles).
		if (!kind.equals("products") && !kind.equals("combos") && !kind.equals("business")) {
			return ResponseEntity.notFound().build();
		}

		Optional<ImageStorageService.StoredImage> image =
				imageStorageService.loadByUrl("/api/images/" + kind + "/" + fileName);

		return image
				.<ResponseEntity<byte[]>>map(stored -> ResponseEntity.ok()
						.contentType(MediaType.parseMediaType(stored.contentType()))
						.cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(30)).cachePublic())
						.body(stored.data()))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
