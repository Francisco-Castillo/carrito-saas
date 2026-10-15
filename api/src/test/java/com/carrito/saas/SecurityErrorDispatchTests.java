package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.DispatcherType;

/**
 * La cadena frente al re-despacho de ERROR del contenedor.
 *
 * <p>Las respuestas de seguridad que no llevan cuerpo —el 403 del
 * {@code AccessDeniedHandler}, el 401 del entry point— hacen que Tomcat
 * re-despache a {@code /error} con {@link DispatcherType#ERROR}. En ese segundo
 * paso el filtro de JWT <strong>ya no corre</strong> (actúa una vez por request),
 * así que el contexto es <em>anónimo</em>: si la cadena deniega su propio
 * renderizado de error, el entry point pisa el status real y un 403 legítimo de
 * un usuario autenticado sale como 401. Medido en vivo mientras se separaban
 * ambos códigos; el bug era latente antes porque <em>toda</em> respuesta de
 * seguridad era 403 y el pisotón daba el mismo código.</p>
 *
 * <p><strong>Ningún test HTTP común puede verlo</strong>: MockMvc no pasa por el
 * contenedor, así que nunca hace ese re-despacho. Por eso acá se fija la
 * <em>decisión de la cadena</em> —el re-despacho de ERROR a una ruta protegida no
 * puede ser denegado— y la verificación en vivo cubre el resto.</p>
 */
@SpringBootTest
class SecurityErrorDispatchTests {

	/**
	 * Ruta sin handler en MVC: queda protegida sólo por
	 * {@code anyRequest().authenticated()}, nunca por un matcher propio, así que
	 * lo que se mide es la decisión de la cadena y no la de la aplicación.
	 */
	private static final String PROTECTED_WITHOUT_HANDLER = "/api/ruta-protegida-sin-handler";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	private MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();
	}

	@Test
	@DisplayName("control: en el dispatch normal esa ruta exige token (401)")
	void protectedRouteRejectsAnonymousOnRequestDispatch() throws Exception {

		// Control indispensable: si esto dejara de ser 401, la aserción del test
		// de abajo pasaría a ser vacua (cualquier cosa distinta de 401/403 la
		// cumpliría sin probar nada).
		mockMvc.perform(get(PROTECTED_WITHOUT_HANDLER))
				.andExpect(result -> assertThat(result.getResponse().getStatus())
						.as("ruta protegida sin handler, sin token: debe ser 401")
						.isEqualTo(401));
	}

	@Test
	@DisplayName("el re-despacho de ERROR a una ruta protegida no es denegado por la cadena")
	void errorDispatchToProtectedRouteIsNotDenied() throws Exception {

		mockMvc.perform(get(PROTECTED_WITHOUT_HANDLER).with(request -> {
					request.setDispatcherType(DispatcherType.ERROR);
					return request;
				}))
				.andExpect(result -> assertThat(result.getResponse().getStatus())
						.as("el re-despacho de ERROR es el renderizado de un error ya decidido, con "
								+ "contexto anónimo porque el filtro de JWT ya no corre: si la cadena lo "
								+ "deniega, el entry point pisa el status real (403 → 401). Actual: %d",
								result.getResponse().getStatus())
						.isNotIn(401, 403));
	}
}
