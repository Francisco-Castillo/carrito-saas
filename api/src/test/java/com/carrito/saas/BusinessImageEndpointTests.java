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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

/**
 * Contract for the business image endpoints: {@code POST /api/business/logo},
 * {@code DELETE /api/business/logo}, {@code POST /api/business/banner} and
 * {@code DELETE /api/business/banner}. The flow mirrors the product image
 * contract: multipart upload stores a UUID-named file under
 * {@code <uploads>/business/}, persists a publicly servable
 * {@code /api/images/business/...} URL in the JWT's business row, replacing
 * deletes the previous file (only when it was our own storage) and DELETE
 * clears the field. Anonymous callers get 401 and unsupported content types
 * are rejected with 400 naming the allowed formats.
 *
 * <p>Uploads are pointed at a JUnit-managed temp dir via the
 * {@code app.uploads.dir} property, so tests never touch the developer's
 * real {@code ./uploads}.</p>
 */
@SpringBootTest
@Transactional
class BusinessImageEndpointTests {

	@BeforeAll
	static void registerUploadsDir() {
		System.setProperty("app.uploads.dir", uploadsDir.toString());
	}

	@DynamicPropertySource
	static void uploadsProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("app.uploads.dir", () -> uploadsDir.toString());
	}

	private static final Long BUSINESS_A_ID = 987_703_201L;

	private static final String SLUG_A = "business-image-a";

	private static final String OWNER_USERNAME = "business-image-owner";

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

	/** Fixed uploads dir registered into the context via @DynamicPropertySource. */
	static final Path uploadsDir = Path.of(System.getProperty("java.io.tmpdir"),
			"business-image-upload-tests-" + UUID.randomUUID());

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, OWNER_USERNAME);
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
					files.forEach(BusinessImageEndpointTests::deleteRecursively);
				}
			}
			Files.deleteIfExists(path);
		} catch (Exception ignored) {
			// best effort cleanup
		}
	}

	private MockMultipartFile pngFile(String name) {
		return new MockMultipartFile("file", name, MediaType.IMAGE_PNG_VALUE, PNG_BYTES);
	}

	private String authHeader() {
		return owner.authorizationHeader();
	}

	@Test
	void anonymousUploadIsUnauthorized() throws Exception {
		mockMvc.perform(multipart("/api/business/logo").file(pngFile("logo.png")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousDeleteIsUnauthorized() throws Exception {
		mockMvc.perform(delete("/api/business/logo"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void uploadLogoStoresFileAndPersistsServableUrl() throws Exception {
		String body = mockMvc.perform(multipart("/api/business/logo")
						.file(pngFile("logo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").isNotEmpty())
				.andReturn()
				.getResponse()
				.getContentAsString();

		String url = com.jayway.jsonpath.JsonPath.read(body, "$.logoUrl");
		assertThat(url).startsWith("/api/images/business/");

		// The file exists on disk under the configured uploads dir.
		String fileName = url.substring("/api/images/business/".length());
		Path stored = uploadsDir.resolve("business").resolve(fileName);
		assertThat(stored).exists();
		assertThat(stored).hasBinaryContent(PNG_BYTES);

		// The stored URL is publicly servable: the storefront shows it.
		mockMvc.perform(get(url))
				.andExpect(status().isOk());
	}

	@Test
	void replacingLogoDeletesThePreviousOwnedFile() throws Exception {
		String first = mockMvc.perform(multipart("/api/business/logo")
						.file(pngFile("logo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String firstUrl = com.jayway.jsonpath.JsonPath.read(first, "$.logoUrl");
		Path firstFile = uploadsDir.resolve("business")
				.resolve(firstUrl.substring("/api/images/business/".length()));

		String second = mockMvc.perform(multipart("/api/business/logo")
						.file(pngFile("logo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String secondUrl = com.jayway.jsonpath.JsonPath.read(second, "$.logoUrl");

		// A NEW file each time (UUID names never collide or overwrite), and
		// the replaced one is gone from disk: no orphans accumulate.
		assertThat(secondUrl).isNotEqualTo(firstUrl);
		assertThat(firstFile).doesNotExist();
		assertThat(uploadsDir.resolve("business")
				.resolve(secondUrl.substring("/api/images/business/".length()))).exists();
	}

	@Test
	void deleteLogoClearsUrlAndFile() throws Exception {
		String body = mockMvc.perform(multipart("/api/business/logo")
						.file(pngFile("logo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andReturn().getResponse().getContentAsString();
		String url = com.jayway.jsonpath.JsonPath.read(body, "$.logoUrl");

		mockMvc.perform(delete("/api/business/logo").header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.logoUrl").isEmpty());

		assertThat(uploadsDir.resolve("business")
				.resolve(url.substring("/api/images/business/".length()))).doesNotExist();

		// Deleting again with nothing set is still a clean 200.
		mockMvc.perform(delete("/api/business/logo").header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk());
	}

	@Test
	void bannerEndpointsMirrorTheLogoContract() throws Exception {
		String body = mockMvc.perform(multipart("/api/business/banner")
						.file(pngFile("banner.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bannerUrl").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		String url = com.jayway.jsonpath.JsonPath.read(body, "$.bannerUrl");
		assertThat(url).startsWith("/api/images/business/");
		assertThat(uploadsDir.resolve("business")
				.resolve(url.substring("/api/images/business/".length()))).exists();

		mockMvc.perform(delete("/api/business/banner").header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bannerUrl").isEmpty());
	}

	@Test
	void unsupportedContentTypeIsRejectedWithTheAllowedFormats() throws Exception {
		MockMultipartFile notAnImage = new MockMultipartFile("file", "logo.txt",
				MediaType.TEXT_PLAIN_VALUE, "not an image".getBytes());

		mockMvc.perform(multipart("/api/business/logo")
						.file(notAnImage)
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Formato de imagen no soportado: usá JPG, PNG o WebP"));
	}

	@Test
	void uploadReplacesAnExternalUrlWithoutTouchingForeignStorage() throws Exception {
		var business = businessRepository.findById(BUSINESS_A_ID).orElseThrow();
		business.setLogoUrl("https://cdn.example.com/old-logo.png");
		businessRepository.saveAndFlush(business);

		String body = mockMvc.perform(multipart("/api/business/logo")
						.file(pngFile("logo.png"))
						.header(HttpHeaders.AUTHORIZATION, authHeader()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		// The external URL is replaced, never fetched or deleted: the delete
		// helper only manages our own /api/images/... namespace.
		String url = com.jayway.jsonpath.JsonPath.read(body, "$.logoUrl");
		assertThat(url).startsWith("/api/images/business/");
	}
}
