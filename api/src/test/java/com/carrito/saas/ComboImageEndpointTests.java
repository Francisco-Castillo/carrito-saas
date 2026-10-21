package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboProductRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.impl.ImageStorageService;

/**
 * Contract for the combo image endpoints: {@code POST /api/combos/{id}/image}
 * and {@code DELETE /api/combos/{id}/image} — the same flow the products
 * already have (multipart upload, replace deletes the previous stored
 * bytes, tenant isolation 404, anonymous 401), stored in Postgres and served
 * publicly under {@code /api/images/combos/**}.
 *
 * <p>The point of the feature (odd/tasks/combo-images.md, S1): the public
 * menu payload carries the combo's OWN image when it has one — the frontend
 * derives it from the components only as a fallback.</p>
 */
@SpringBootTest
@Transactional
class ComboImageEndpointTests {

	private static final Long BUSINESS_A_ID = 987_716_101L;
	private static final Long BUSINESS_B_ID = 987_716_102L;

	private static final String SLUG_A = "combo-image-a";
	private static final String SLUG_B = "combo-image-b";

	private static final String OWNER_USERNAME = "combo-image-owner";

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
	private ComboRepository comboRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ComboProductRepository comboProductRepository;

	@Autowired
	private ImageStorageService imageStorageService;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	private Category categoryA;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);

		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Business businessB = BusinessAuthSeed.seedBusiness(businessRepository, BUSINESS_B_ID, SLUG_B);

		categoryA = categoryRepository.saveAndFlush(category(businessA, "Combo Image Cat A"));
		categoryRepository.saveAndFlush(category(businessB, "Combo Image Cat B"));
	}

	private Category category(Business business, String name) {
		Category c = new Category();
		c.setBusiness(business);
		c.setName(name);
		return c;
	}

	private Combo combo(Category cat, String name) {
		Product product = new Product();
		product.setCategory(cat);
		product.setName("Producto de " + name);
		product.setPrice(new java.math.BigDecimal("5000.00"));
		product.setCost(new java.math.BigDecimal("1000.00"));
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		ComboProduct item = new ComboProduct();
		item.setProduct(product);
		item.setQuantity(new java.math.BigDecimal("1"));

		// La colección se arma ANTES del save: el query de tenant usa
		// JOIN FETCH c.items (inner join, un combo sin items elimina la fila) y
		// la entidad gestionada no repuebla una colección nunca inicializada.
		Combo combo = new Combo();
		combo.setCategory(cat);
		combo.setName(name);
		combo.setPrice(new java.math.BigDecimal("9000.00"));
		combo.setActive(true);
		combo.setItems(new java.util.ArrayList<>(java.util.List.of(item)));
		item.setCombo(combo);
		return comboRepository.saveAndFlush(combo);
	}

	private MockMultipartFile pngFile(String name) {
		return new MockMultipartFile("file", name, MediaType.IMAGE_PNG_VALUE, PNG_BYTES);
	}

	private String authHeader() {
		return owner.authorizationHeader();
	}

	// ------------------------------------------------------------------
	// S1: la imagen propia del combo, en el endpoint y en el menú
	// ------------------------------------------------------------------

	@Test
	void uploadStoresFilePersistsUrlAndTheMenuPayloadCarriesIt() throws Exception {
		Combo combo = combo(categoryA, "Combo Imagen");

		String body = mockMvc.perform(multipart("/api/combos/{id}/image", combo.getId())
						.file(pngFile("combo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.imageUrl").isNotEmpty())
				.andReturn()
				.getResponse()
				.getContentAsString();

		String url = com.jayway.jsonpath.JsonPath.read(body, "$.imageUrl");
		assertThat(url).startsWith("/api/images/combos/");

		// Los bytes viven en Postgres: mismo contenido, content type correcto.
		var stored = imageStorageService.loadByUrl(url);
		assertThat(stored).isPresent();
		assertThat(stored.get().contentType()).isEqualTo(MediaType.IMAGE_PNG_VALUE);
		assertThat(stored.get().data()).isEqualTo(PNG_BYTES);

		// Y el payload PÚBLICO del menú lleva la imagen propia del combo.
		mockMvc.perform(get("/api/menu/{slug}", SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.combos[0].imageUrl").value(url));
	}

	@Test
	void deleteClearsTheUrlAndTheMenuPayloadStopsCarryingIt() throws Exception {
		Business businessA = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		Combo combo = combo(categoryA, "Combo Imagen Borrable");

		mockMvc.perform(multipart("/api/combos/{id}/image", combo.getId())
						.file(pngFile("combo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/api/combos/{id}/image", combo.getId())
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk());

		// La fila queda sin imagen: el menú deja de llevar imageUrl (el
		// frontend cae al fallback de derivar por componente).
		mockMvc.perform(get("/api/menu/{slug}", SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.combos[0].imageUrl").value(org.hamcrest.Matchers.nullValue()));
	}

	// ------------------------------------------------------------------
	// Seguridad y aislamiento: mismos contratos que los productos
	// ------------------------------------------------------------------

	@Test
	void anonymousUploadIsUnauthorized() throws Exception {
		Combo combo = combo(categoryA, "Combo Anon");

		mockMvc.perform(multipart("/api/combos/{id}/image", combo.getId()).file(pngFile("combo.png")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void foreignComboIdIsNotFound() throws Exception {
		Business businessB = businessRepository.findById(BUSINESS_B_ID).orElseThrow();
		Category categoryB = categoryRepository.findAll().stream()
				.filter(c -> c.getBusiness().getId().equals(BUSINESS_B_ID))
				.findFirst()
				.orElseThrow();
		Combo foreign = combo(categoryB, "Combo Foreign");

		// Owner A upload on B's combo: mismo contrato de aislamiento que el
		// resto del CRUD (404, nunca un éxito silencioso).
		mockMvc.perform(multipart("/api/combos/{id}/image", foreign.getId())
						.file(pngFile("combo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isNotFound());
	}

	@Test
	void unsupportedContentTypeIsRejectedWithSpanishMessage() throws Exception {
		Combo combo = combo(categoryA, "Combo Tipo");

		MockMultipartFile text = new MockMultipartFile("file", "notas.txt", MediaType.TEXT_PLAIN_VALUE,
				"no soy una imagen".getBytes());

		mockMvc.perform(multipart("/api/combos/{id}/image", combo.getId())
						.file(text)
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").isNotEmpty());
	}
}
