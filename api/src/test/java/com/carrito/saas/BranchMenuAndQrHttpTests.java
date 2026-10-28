package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Branch;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BranchRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

/**
 * Menú público con selector de sucursales + QR por sucursal
 * (multi-sucursal, odd/tasks/multi-sucursal.md, S3/T6 y S4/T7).
 *
 * <p>Reglas bajo prueba:</p>
 * <ul>
 *   <li>El payload del menú público trae {@code branches}: sólo las
 *       sucursales ACTIVAS, con id/name/address, ordenadas por id — es la
 *       lista que alimenta el selector del storefront (S4). Sin sucursales,
 *       la lista viaja vacía (nunca ausente).</li>
 *   <li>Un parámetro {@code ?sucursal=} desconocido/ajeno/inactivo NO rompe
 *       la carta: es superficie pública de display, cae silenciosamente a la
 *       default (asimetría con los pedidos, que son fail-closed 404).
 *       NOTA T7: la parte que REFLEJA hours/whatsapp de la sucursal pedida
 *       queda pendiente de aprobar la superficie {@code IMenuService}
 *       (sobrecarga {@code getMenu(slug, branchId)}); este test fija la
 *       mitad no rompible del contrato, que ya rige.</li>
 *   <li>QR por sucursal ({@code GET /api/business/branches/{id}/qr}): el
 *       negocio sale del JWT; sucursal propia y activa → PNG real que
 *       codifica la URL del menú CON {@code ?sucursal=<id>}; ajena,
 *       inexistente o inactiva → 404 directo con ErrorResponse (no
 *       ResponseStatusException, mismo contrato que GET /api/business/qr).
 *       El endpoint del grupo ({@code /api/business/qr}) queda intacto como
 *       QR del grupo/default.</li>
 * </ul>
 *
 * <p>MockMvc se arma a mano con el {@link FilterChainProxy} real (mismo
 * patrón que {@code BusinessQrEndpointTests}); los QR se decodifican con
 * ZXing para fijar EXACTAMENTE la URL que codifican.</p>
 */
@SpringBootTest
@Transactional
class BranchMenuAndQrHttpTests {

	private static final Long BUSINESS_A_ID = 987_905_201L;
	private static final Long BUSINESS_B_ID = 987_905_202L;

	private static final String SLUG_A = "branch-menu-a";
	private static final String SLUG_B = "branch-menu-b";

	private static final String USERNAME_A = "branch-menu-owner-a";
	private static final String USERNAME_B = "branch-menu-owner-b";

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
	private BranchRepository branchRepository;

	@Autowired
	private JwtUtil jwtUtil;

	private MockMvc mockMvc;

	private BusinessAuthSeed.AuthenticatedBusiness ownerA;
	private BusinessAuthSeed.AuthenticatedBusiness ownerB;

	private Long casaCentralId;
	private Long palermoId;

	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
			new com.fasterxml.jackson.databind.ObjectMapper();

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		ownerA = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_A_ID, SLUG_A, USERNAME_A);

		ownerB = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_B_ID, SLUG_B, USERNAME_B);

		// Negocio A: dos sucursales activas (el caso del selector público).
		casaCentralId = seedBranch(BUSINESS_A_ID, "Casa central", "Av. Siempre Viva 742");
		palermoId = seedBranch(BUSINESS_A_ID, "Palermo", "Calle Falsa 123");
	}

	// ------------------------------------------------------------------
	// Fixtures
	// ------------------------------------------------------------------

	private Long seedBranch(long businessId, String name, String address) {
		Business business = businessRepository.findById(businessId).orElseThrow();
		Branch branch = new Branch();
		branch.setBusiness(business);
		branch.setName(name);
		branch.setAddress(address);
		return branchRepository.saveAndFlush(branch).getId();
	}

	// ------------------------------------------------------------------
	// (a) Menú público: lista de sucursales activas para el selector
	// ------------------------------------------------------------------

	@Test
	void menuPublicoConDosSucursalesActivasTraeLaListaOrdenada() throws Exception {
		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.branches.length()").value(2))
				.andExpect(jsonPath("$.branches[0].id").value(casaCentralId))
				.andExpect(jsonPath("$.branches[0].name").value("Casa central"))
				.andExpect(jsonPath("$.branches[0].address").value("Av. Siempre Viva 742"))
				.andExpect(jsonPath("$.branches[1].id").value(palermoId))
				.andExpect(jsonPath("$.branches[1].name").value("Palermo"))
				.andExpect(jsonPath("$.branches[1].address").value("Calle Falsa 123"));
	}

	@Test
	void menuPublicoSinSucursalesTraeLaListaVacia() throws Exception {
		// El negocio B fue sembrado sin sucursales: la carta no cambia de
		// forma (cero regresión), la lista viaja vacía — nunca ausente.
		mockMvc.perform(get("/api/menu/" + SLUG_B))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.branches").isEmpty());
	}

	// ------------------------------------------------------------------
	// (b) ?sucursal= propia y activa REFLEJA hours/whatsapp de esa sucursal
	// ------------------------------------------------------------------

	@Test
	void menuConSucursalReflejaSusHorasYWhatsApp() throws Exception {
		// Casa central: lunes abierto 09:00–12:00 (única sucursal con horas).
		String tokenCasa = selectBranch(ownerA.bearerToken(), casaCentralId);
		pockHours(tokenCasa, "[{\"dayOfWeek\": 1, \"shifts\": [{\"opensAt\": \"09:00\", \"closesAt\": \"12:00\"}]}]");

		// Palermo: lunes cerrado (lista vacía = reemplazo en bloque) y WhatsApp propio.
		String tokenPalermo = selectBranch(ownerA.bearerToken(), palermoId);
		pockHours(tokenPalermo, "[{\"dayOfWeek\": 1, \"shifts\": []}]");
		mockMvc.perform(put("/api/business/branches/" + palermoId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenPalermo)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"Palermo\", \"address\": \"Calle Falsa 123\", \"phone\": \"5491199998888\"}"))
				.andExpect(status().isOk());

		// Con ?sucursal= de Palermo: SU whatsapp y SUS horas (lunes cerrado).
		mockMvc.perform(get("/api/menu/" + SLUG_A).param("sucursal", String.valueOf(palermoId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.whatsappNumber").value("5491199998888"))
				.andExpect(jsonPath("$.business.hours[0].shifts").isEmpty());

		// Sin parámetro: la default (Casa central) — whatsapp del negocio y
		// lunes abierto 09:00–12:00.
		mockMvc.perform(get("/api/menu/" + SLUG_A))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.whatsappNumber").doesNotExist())
				.andExpect(jsonPath("$.business.hours[0].shifts[0].opensAt").value("09:00:00"));

		// Con ?sucursal= inválida: fallback silencioso a la default — misma
		// carta que sin parámetro (lunes abierto de Casa central).
		mockMvc.perform(get("/api/menu/" + SLUG_A).param("sucursal", "999999"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.hours[0].shifts[0].opensAt").value("09:00:00"));
	}

	/** PUT /api/business/profile con las horas del payload (sólo horas). */
	private void pockHours(String bearerTokenWithoutPrefix, String hoursJson) throws Exception {
		mockMvc.perform(put("/api/business/profile")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerTokenWithoutPrefix)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"hours\": %s }".formatted(hoursJson)))
				.andExpect(status().isOk());
	}

	private String selectBranch(String bearerTokenWithoutPrefix, long branchId) throws Exception {
		String response = mockMvc.perform(post("/api/business/branches/select/" + branchId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerTokenWithoutPrefix))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).path("token").asText();
	}

	// ------------------------------------------------------------------
	// (c) ?sucursal= inválido NO rompe la carta (fallback silencioso)
	// ------------------------------------------------------------------

	@Test
	void menuPublicoConSucursalInvalidaCaeALaDefaultSinError() throws Exception {
		// Display público: un link con una sucursal dada de baja (o ajena, o
		// inexistente) no puede romper la carta del cliente — asimetría con
		// los pedidos, que son fail-closed 404. La mitad que REFLEJA
		// hours/whatsapp de la sucursal pedida es T7-pendiente de la
		// superficie IMenuService (ver javadoc de la clase).
		mockMvc.perform(get("/api/menu/" + SLUG_A).param("sucursal", "999999"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.business.name").exists())
				.andExpect(jsonPath("$.branches.length()").value(2));
	}

	// ------------------------------------------------------------------
	// (d) QR de sucursal propia: PNG que codifica ?sucursal=<id>
	// ------------------------------------------------------------------

	@Test
	void qrDeSucursalPropiaEsUnPngQueCodificaLaUrlConSucursal() throws Exception {
		byte[] body = mockMvc.perform(get("/api/business/branches/" + casaCentralId + "/qr")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(body.length)
				.as("el QR debe ser una imagen real, no un cuerpo vacío")
				.isGreaterThan(100);
		assertThat(new int[] { body[0] & 0xFF, body[1], body[2], body[3] })
				.as("el cuerpo debe arrancar con los magic bytes PNG 89 50 4E 47")
				.containsExactly(0x89, 0x50, 0x4E, 0x47);

		assertThat(decodeQrText(body))
				.as("el QR de la sucursal debe codificar la URL del menú con ?sucursal=<id>")
				.isEqualTo("http://localhost:9090/menu/?sucursal=" + casaCentralId);
	}

	// ------------------------------------------------------------------
	// (e) QR de sucursal ajena → 404
	// ------------------------------------------------------------------

	@Test
	void qrDeSucursalAjenaResuelve404() throws Exception {
		mockMvc.perform(get("/api/business/branches/" + casaCentralId + "/qr")
						.header(HttpHeaders.AUTHORIZATION, ownerB.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------
	// (f) QR de sucursal inactiva → 404
	// ------------------------------------------------------------------

	@Test
	void qrDeSucursalInactivaResuelve404() throws Exception {
		// Baja lógica vía el endpoint del panel (elimina = active=false).
		mockMvc.perform(delete("/api/business/branches/" + palermoId)
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isOk());
		Branch dadaDeBaja = branchRepository.findById(palermoId).orElseThrow();
		assertThat(dadaDeBaja.getActive()).as("la baja lógica dejó la sucursal inactiva").isFalse();

		mockMvc.perform(get("/api/business/branches/" + palermoId + "/qr")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------
	// (g) El QR del grupo (/api/business/qr) queda intacto
	// ------------------------------------------------------------------

	@Test
	void qrDelGrupoSigueCodificandoLaUrlSinSucursal() throws Exception {
		byte[] body = mockMvc.perform(get("/api/business/qr")
						.header(HttpHeaders.AUTHORIZATION, ownerA.authorizationHeader()))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG))
				.andReturn()
				.getResponse()
				.getContentAsByteArray();

		assertThat(decodeQrText(body))
				.as("el QR del grupo NO cambia: sigue apuntando al menú sin sucursal")
				.isEqualTo("http://localhost:9090/menu/");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/**
	 * Igual que {@code BusinessQrEndpointTests}: el PNG se decodifica de vuelta.
	 *
	 * <p>Con {@code TRY_HARDER} y un reintento sobre la imagen ampliada: el
	 * decodificador sin pistas fallaba de forma intermitente con el QR del
	 * tamaño que genera el servicio (zxing {@code NotFoundException}), lo que
	 * ensuciaba la suite completa con un fallo que no era del código bajo
	 * prueba. El dato esperado no cambia — el QR sigue decodificando a la URL
	 * exacta —, sólo se le da al decodificador una segunda oportunidad.</p>
	 */
	private String decodeQrText(byte[] png) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

			var hints = Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE);

			try {
				return decode(image, hints);
			}
			catch (NotFoundException firstTry) {
				// Reintento con la imagen al doble: mismo QR, más píxeles.
				return decode(scaleUp(image, 2), hints);
			}
		}
		catch (Exception ex) {
			throw new AssertionError("el PNG devuelto no decodifica como QR", ex);
		}
	}

	private String decode(BufferedImage image, Map<DecodeHintType, ?> hints) throws Exception {
		return new QRCodeReader()
				.decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))), hints)
				.getText();
	}

	private BufferedImage scaleUp(BufferedImage image, int factor) {
		int width = image.getWidth() * factor;
		int height = image.getHeight() * factor;
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = scaled.createGraphics();
		g.drawImage(image, 0, 0, width, height, null);
		g.dispose();
		return scaled;
	}

}
