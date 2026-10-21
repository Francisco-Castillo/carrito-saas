/*
 * Image storage persistence tests — DB-backed uploaded images.
 *
 * The demo runs on Render's free tier: the container filesystem (/tmp) is
 * WIPED on every spin-down (~15 min idle) and every redeploy, while the
 * image URLs persist in product/combo/business rows — broken images for the
 * owner. The storage moves to Postgres (BYTEA): same public URL contract
 * (/api/images/{kind}/<uuid>.<ext>), same validation, but the bytes survive
 * anything the container lives through.
 *
 * RED harness: the uploads dir is pointed at an UNWRITABLE path. The old
 * filesystem implementation fails to store; the DB implementation must not
 * care about the filesystem at all.
 *
 * Plain @SpringBootTest (single extra context for the custom property);
 * rows are cleaned after every test so the real demo DB stays untouched.
 */
package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.carrito.saas.service.impl.ImageStorageService;

@SpringBootTest(properties = "app.uploads.dir=/proc/no-such-dir/uploads")
class ImageStorageDbPersistenceTests {

	/** 1x1 red PNG (67 bytes) — enough to prove bytes round-trip untouched. */
	private static final byte[] PNG = new byte[] {
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
			0x49, 0x48, 0x44, 0x52
	};

	@Autowired
	private ImageStorageService imageStorageService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanUploadedImages() {
		jdbcTemplate.update("DELETE FROM uploaded_images");
	}

	@Test
	void storeWorksEvenWhenTheUploadsDirIsUnwritable() {
		final String url = imageStorageService.store(PNG, "image/png");

		assertThat(url).startsWith("/api/images/products/").endsWith(".png");
	}

	@Test
	void storeComboAndBusinessImagesRoundTripThroughTheirOwnUrlPrefixes() {
		final String comboUrl = imageStorageService.storeCombo(PNG, "image/png");
		final String businessUrl = imageStorageService.storeBusinessImage(PNG, "image/jpeg");

		assertThat(comboUrl).startsWith("/api/images/combos/").endsWith(".png");
		assertThat(businessUrl).startsWith("/api/images/business/").endsWith(".jpg");
	}

	@Test
	void loadByUrlReturnsTheExactBytesAndContentType() {
		final String url = imageStorageService.store(PNG, "image/png");

		final Optional<ImageStorageService.StoredImage> loaded = imageStorageService.loadByUrl(url);

		assertThat(loaded).isPresent();
		assertThat(loaded.get().contentType()).isEqualTo("image/png");
		assertThat(loaded.get().data()).isEqualTo(PNG);
	}

	@Test
	void loadByUrlRejectsForeignUrlsAndMissingIds() {
		assertThat(imageStorageService.loadByUrl("https://cdn.example.com/foo.png")).isEmpty();
		assertThat(imageStorageService.loadByUrl("/api/images/products/00000000-0000-0000-0000-000000000000.png"))
				.isEmpty();
		assertThatCode(() -> imageStorageService.loadByUrl(null)).doesNotThrowAnyException();
	}

	@Test
	void deleteByUrlRemovesTheStoredBytes() {
		final String url = imageStorageService.store(PNG, "image/png");
		assertThat(imageStorageService.loadByUrl(url)).isPresent();

		imageStorageService.deleteByUrl(url);

		assertThat(imageStorageService.loadByUrl(url)).isEmpty();
	}
}
