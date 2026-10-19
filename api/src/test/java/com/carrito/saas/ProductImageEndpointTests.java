package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.DynamicPropertySource;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Contract for the product image endpoints: {@code POST /api/products/{id}/image}
 * (multipart upload, replace deletes the old file on disk) and
 * {@code DELETE /api/products/{id}/image}. Security matches the rest of the
 * admin CRUD — anonymous is 401 and a foreign product id is 404 — and
 * unsupported content types are rejected with 400 naming the allowed formats.
 * The stored URL is publicly servable under {@code /api/images/**}.
 *
 * <p>Uploads are pointed at a JUnit-managed temp dir via the
 * {@code app.uploads.dir} property, so tests never touch the developer's
 * real {@code ./uploads}.</p>
 */
@SpringBootTest
@Transactional
class ProductImageEndpointTests {

	@BeforeAll
	static void registerUploadsDir() {
		System.setProperty("app.uploads.dir", uploadsDir.toString());
	}

	@DynamicPropertySource
	static void uploadsProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("app.uploads.dir", () -> uploadsDir.toString());
	}

	private static final Long BUSINESS_A_ID = 987_702_201L;
	private static final Long BUSINESS_B_ID = 987_702_202L;

	private static final String SLUG_A = "product-image-a";
	private static final String SLUG_B = "product-image-b";

	private static final String OWNER_USERNAME = "product-image-owner";

	private static final byte[] PNG_BYTES = new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	/** Fixed uploads dir registered into the context via @DynamicPropertySource. */
	static final Path uploadsDir = Path.of(System.getProperty("java.io.tmpdir"),
			"product-image-upload-tests-" + UUID.randomUUID());

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;
	private Category categoryB;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Image Cat A"));
		categoryB = categoryRepository.saveAndFlush(category(businessB, "Image Cat B"));
	}

	@AfterEach
	void cleanUploads() throws Exception {
		if (Files.exists(uploadsDir)) {
			try (var files = Files.list(uploadsDir)) {
				files.forEach(path -> deleteRecursively(path));
			}
		}
	}

	private static void deleteRecursively(Path path) {
		try {
			if (Files.isDirectory(path)) {
				try (var files = Files.list(path)) {
					files.forEach(ProductImageEndpointTests::deleteRecursively);
				}
			}
			Files.deleteIfExists(path);
		} catch (Exception ignored) {
			// best effort cleanup
		}
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Product product(Category category, String name) {
		Product p = new Product();
		p.setCategory(category);
		p.setName(name);
		p.setPrice(new java.math.BigDecimal("100.00"));
		p.setCost(new java.math.BigDecimal("20.00"));
		p.setActive(true);
		return productRepository.saveAndFlush(p);
	}

	private MockMultipartFile pngFile(String name) {
		return new MockMultipartFile("file", name, MediaType.IMAGE_PNG_VALUE, PNG_BYTES);
	}

	private String authHeader() {
		return owner.authorizationHeader();
	}

	@Test
	void anonymousUploadIsUnauthorized() throws Exception {
		Product p = product(categoryA, "Image Anon");

		mockMvc.perform(multipart("/api/products/{id}/image", p.getId()).file(pngFile("foto.png")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousDeleteIsUnauthorized() throws Exception {
		Product p = product(categoryA, "Image Anon Del");

		mockMvc.perform(delete("/api/products/{id}/image", p.getId()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void foreignProductIdIsNotFound() throws Exception {
		Product foreign = product(categoryB, "Image Foreign");

		// Owner A upload on B's product: same tenant-isolation contract as the
		// rest of the CRUD (404, never a silent success).
		mockMvc.perform(multipart("/api/products/{id}/image", foreign.getId())
						.file(pngFile("foto.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isNotFound());
	}

	@Test
	void uploadStoresFileAndPersistsServableUrl() throws Exception {
		Product p = product(categoryA, "Image Happy");

		String body = mockMvc.perform(multipart("/api/products/{id}/image", p.getId())
						.file(pngFile("foto.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.imageUrl").isNotEmpty())
				.andReturn()
				.getResponse()
				.getContentAsString();

		String url = com.jayway.jsonpath.JsonPath.read(body, "$.imageUrl");
		assertThat(url).startsWith("/api/images/products/");

		// The file exists on disk under the configured uploads dir.
		String fileName = url.substring("/api/images/products/".length());
		Path stored = uploadsDir.resolve("products").resolve(fileName);
		assertThat(stored).exists();
		assertThat(stored).hasBinaryContent(PNG_BYTES);

		// The URL is publicly servable (no token): the digital menu needs it.
		mockMvc.perform(get(url)).andExpect(status().isOk());

		// The entity kept the URL.
		Product reloaded = productRepository.findById(p.getId()).orElseThrow();
		assertThat(reloaded.getImageUrl()).isEqualTo(url);
	}

	@Test
	void uploadReplacesOldFileAndDeletesItFromDisk() throws Exception {
		Product p = product(categoryA, "Image Replace");

		String firstUrl = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(multipart("/api/products/{id}/image", p.getId())
						.file(pngFile("una.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(),
				"$.imageUrl");

		String secondUrl = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(multipart("/api/products/{id}/image", p.getId())
						.file(pngFile("dos.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(),
				"$.imageUrl");

		assertThat(secondUrl).isNotEqualTo(firstUrl);
		assertThat(uploadsDir.resolve("products")
				.resolve(firstUrl.substring("/api/images/products/".length()))).doesNotExist();
		assertThat(uploadsDir.resolve("products")
				.resolve(secondUrl.substring("/api/images/products/".length()))).exists();
	}

	@Test
	void unsupportedContentTypeIsRejectedWith400() throws Exception {
		Product p = product(categoryA, "Image Bad Type");

		MockMultipartFile gif = new MockMultipartFile("file", "anim.gif", "image/gif", new byte[] { 'G', 'I', 'F' });

		String body = mockMvc.perform(multipart("/api/products/{id}/image", p.getId())
						.file(gif)
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("Formato de imagen no soportado");

		// The rejection leaves the product untouched and stores nothing.
		assertThat(productRepository.findById(p.getId()).orElseThrow().getImageUrl()).isNull();
	}

	@Test
	void emptyFileIsRejectedWith400() throws Exception {
		Product p = product(categoryA, "Image Empty");

		MockMultipartFile empty = new MockMultipartFile("file", "vacio.png", MediaType.IMAGE_PNG_VALUE, new byte[0]);

		mockMvc.perform(multipart("/api/products/{id}/image", p.getId())
						.file(empty)
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();
	}

	@Test
	void deleteRemovesUrlAndFile() throws Exception {
		Product p = product(categoryA, "Image Delete");

		String url = com.jayway.jsonpath.JsonPath.read(
				mockMvc.perform(multipart("/api/products/{id}/image", p.getId())
						.file(pngFile("borrar.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(),
				"$.imageUrl");

		mockMvc.perform(delete("/api/products/{id}/image", p.getId())
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.nullValue()));

		assertThat(uploadsDir.resolve("products")
				.resolve(url.substring("/api/images/products/".length()))).doesNotExist();
		assertThat(productRepository.findById(p.getId()).orElseThrow().getImageUrl()).isNull();
	}
}
