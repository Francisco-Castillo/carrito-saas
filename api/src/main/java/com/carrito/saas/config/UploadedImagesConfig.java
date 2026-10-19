package com.carrito.saas.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serving público de las imágenes de producto que sube el negocio: los
 * archivos viven en {@code <app.uploads.dir>/products/} (fuera del
 * classpath, escritos por {@code ImageStorageService} con nombre UUID) y se
 * sirven en {@code /api/images/products/**} sin token, igual que
 * {@code /dev-media/**}: la carta digital pública necesita mostrarlas.
 * Spring resuelve el resource handler con su propia sanitización de path
 * (traversal incluido), y los nombres nunca vienen del cliente.
 */
@Configuration
public class UploadedImagesConfig implements WebMvcConfigurer {

	private final String uploadsDir;

	public UploadedImagesConfig(@Value("${app.uploads.dir:./uploads}") String uploadsDir) {
		this.uploadsDir = uploadsDir;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = Path.of(uploadsDir, "products").toAbsolutePath().normalize().toUri().toString();
		if (!location.endsWith("/")) {
			location += "/";
		}
		registry.addResourceHandler("/api/images/products/**").addResourceLocations(location);
	}
}
