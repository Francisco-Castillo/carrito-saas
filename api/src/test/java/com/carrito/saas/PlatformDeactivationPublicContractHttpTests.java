package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;

/**
 * PUBLIC contract of the logical deactivation (S5 of
 * odd/tasks/plataforma-editar-baja.md): a deactivated business simply does
 * not exist for the public surface —
 *
 * <ul>
 * <li>{@code GET /api/menu/{slug}} answers 404 (the carta is gone);</li>
 * <li>{@code GET /api/restaurants/slug/{slug}} answers 404 (the login
 * lookup finds nothing);</li>
 * <li>{@code POST /api/orders/menu/{slug}} answers 404 (no orders into a
 * deactivated business) — and it answers 404 BEFORE the closed-hours check
 * answers its 400: a deactivated business is not "closed", it is not
 * there.</li>
 * </ul>
 *
 * <p>The gate fires AFTER the business resolution and BEFORE the
 * open-hours validation. Internal owner scopes (dashboard, profile) do NOT
 * gate on active — they are not public and stay intact (S5).</p>
 *
 * <p>The ACTIVE counterparts answer their own data (menu 200) — the gate
 * only removes deactivated businesses, it never touches live ones.</p>
 */
@SpringBootTest
@Transactional
class PlatformDeactivationPublicContractHttpTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	private String run;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		run = "dc" + System.nanoTime();
	}

	@Test
	@DisplayName("GET /api/menu/{slug} answers 404 for a deactivated business and 200 for a live one")
	void menuIsGoneForDeactivatedBusiness() throws Exception {

		Business deactivated = seedBusiness("baja-" + run, false);
		Business live = seedBusiness("viva-" + run, true);

		mockMvc.perform(get("/api/menu/baja-" + run))
				.andExpect(status().isNotFound());

		mockMvc.perform(get("/api/menu/viva-" + run))
				.andExpect(status().isOk());

		assertThat(deactivated.getId()).isNotNull();
		assertThat(live.getId()).isNotNull();
	}

	@Test
	@DisplayName("GET /api/restaurants/slug/{slug} answers 404 for a deactivated business")
	void restaurantLookupIsGoneForDeactivatedBusiness() throws Exception {

		seedBusiness("baja-" + run, false);

		mockMvc.perform(get("/api/restaurants/slug/baja-" + run))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("POST /api/orders/menu/{slug} answers 404 — BEFORE the closed-hours 400")
	void ordersIntoDeactivatedBusinessAnswer404() throws Exception {

		seedBusiness("baja-" + run, false);

		// Minimal VALID payload: field validation passes, the business
		// resolves, and the deactivation gate must answer 404 before the
		// closed-hours rule could answer its 400 (no hours seeded — the
		// peer's rule would call it closed; deactivated is not "closed").
		String body = """
				{
				  "customerName": "Cliente %s",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO",
				  "items": [{"productId": 1, "quantity": 1}]
				}
				""".formatted(run);

		mockMvc.perform(post("/api/orders/menu/baja-" + run)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());
	}

	// ==================================================================
	// Fixture
	// ==================================================================

	private Business seedBusiness(String slug, boolean active) {

		Business business = BusinessAuthSeed.seedBusiness(businessRepository,
				987_904_700L + (slug.hashCode() & 0xff), slug);
		business.setActive(active);
		return businessRepository.saveAndFlush(business);
	}

}
