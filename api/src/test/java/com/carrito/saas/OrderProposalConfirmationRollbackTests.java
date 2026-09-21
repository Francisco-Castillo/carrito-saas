package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The ONE case of T7a.2 that cannot live in a {@code @Transactional} suite:
 * a FAILED writer must ROLL THE CLAIM BACK — a fresh read must show the
 * proposal {@code PENDING} with {@code order_id} NULL and stock unchanged
 * ({@code odd/tasks/whatsapp-inbound.md}, eje 4; the property that makes
 * retry possible and the reason the claim lives in the same transaction as
 * {@code createOrder}).
 *
 * <p><strong>Why this class is deliberately NOT {@code @Transactional}</strong>
 * (the escape hatch the feature document prescribed, with the
 * {@code WhatsappIdempotencyRaceTests} pattern; gaps 37/39/40 — MEASURED, not
 * assumed): in a transactional test the service's REQUIRED transaction JOINS
 * the test transaction, so the failed writer only marks it rollback-only —
 * the already-executed CAS UPDATE stays applied and a fresh read sees
 * {@code CONFIRMED}, never the rolled-back {@code PENDING}. Here the service
 * runs in its OWN real transaction against PostgreSQL, so the rollback is the
 * real thing.</p>
 *
 * <p>Fixture isolation comes from precisely scoped {@code @BeforeEach}/
 * {@code @AfterEach} cleanup, not from rollback (no test-managed transaction
 * exists here). The suite-unique marker is the sender phone: it selects the
 * proposals, and every other row is scoped by this suite's own fixed business
 * id. Cleanup is CHILD-BEFORE-PARENT because bulk JPQL deletes bypass
 * cascade (open gap 40): order_items, then orders, then proposal items, then
 * proposals, then catalog, then auth rows, then the business — inside ONE
 {@code TransactionTemplate} transaction, since there is no test-managed
 * one. This marker and id are NOT shared with any other suite.</p>
 */
@SpringBootTest
class OrderProposalConfirmationRollbackTests {

	/** Fixed high id, DISTINCT from every other suite's business id. */
	private static final Long BUSINESS_ID = 987_700_911L;
	private static final String SLUG = "confirm-rollback";
	private static final String USERNAME = "rollback-owner";

	/**
	 * Sender phone UNIQUE to this suite (open gap 39): fixtures, assertions
	 * and cleanup all select by it. Not shared with any other suite — grep
	 * the repository for this value to keep that property true.
	 */
	private static final String SUITE_FROM_PHONE = "5491176543211";

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

	@Autowired
	private OrderProposalRepository orderProposalRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private TransactionTemplate txTemplate;

	private MockMvc mockMvc;

	/**
	 * Ids of the role rows THIS run created (the seed's {@code OWNER} role
	 * minus the {@code OWNER} ids that already existed before seeding),
	 * captured in {@code setUp}. Cleanup deletes exactly these ids and
	 * nothing else: other suites and demo data share the name {@code OWNER},
	 * so deletion by bare name would remove rows this suite does not own
	 * (the exact residue bug classes of gaps 39/40). Null until seeding has
	 * run, so the pre-seed invocation of {@code cleanOwnRows} is a no-op for
	 * roles.
	 */
	private Set<Long> ownRoleIds;

	@Autowired
	void setUpTransactionTemplate(PlatformTransactionManager transactionManager) {

		this.txTemplate = new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void setUp() {

		cleanOwnRows();

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.addFilters(springSecurityFilterChain)
				.build();

		// Capture BEFORE seeding so only the ids created by THIS run are
		// deletable afterwards (open gap 39: delete only what you own).
		Set<Long> preExisting = new HashSet<>(entityManager.createQuery(
					"select r.id from Role r where r.name = 'OWNER'", Long.class)
				.getResultList());

		BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, USERNAME);

		ownRoleIds = new HashSet<>(entityManager.createQuery(
					"select r.id from Role r where r.name = 'OWNER'", Long.class)
				.getResultList());
		ownRoleIds.removeAll(preExisting);
	}

	@AfterEach
	void cleanOwnRows() {

		// Child-before-parent (open gap 40), all scoped by this suite's own
		// rows, in ONE transaction (no test-managed one exists here).
		txTemplate.executeWithoutResult(tx -> {
			entityManager.createQuery(
					"DELETE FROM OrderItem oi WHERE oi.order.business.id = :bid")
					.setParameter("bid", BUSINESS_ID)
					.executeUpdate();
			entityManager.createQuery(
					"DELETE FROM Order o WHERE o.business.id = :bid")
					.setParameter("bid", BUSINESS_ID)
					.executeUpdate();
			entityManager.createQuery(
					"DELETE FROM OrderProposalItem i WHERE i.proposal.fromPhone = :phone")
					.setParameter("phone", SUITE_FROM_PHONE)
					.executeUpdate();
			entityManager.createQuery(
					"DELETE FROM OrderProposal p WHERE p.fromPhone = :phone")
					.setParameter("phone", SUITE_FROM_PHONE)
					.executeUpdate();
			entityManager.createQuery(
					"DELETE FROM Product pr WHERE pr.category.business.id = :bid")
					.setParameter("bid", BUSINESS_ID)
					.executeUpdate();
			entityManager.createQuery(
					"DELETE FROM Category c WHERE c.business.id = :bid")
					.setParameter("bid", BUSINESS_ID)
					.executeUpdate();
			entityManager.createQuery(
					"DELETE FROM BusinessUser bu WHERE bu.business.id = :bid")
					.setParameter("bid", BUSINESS_ID)
					.executeUpdate();
			userRepository.findByUsername(USERNAME).ifPresent(userRepository::delete);
			// Entity-based deletes (user + its user_roles join rows) execute at
			// flush; the bulk role delete below would not trigger that flush by
			// itself (query-space AUTO flush does not overlap), so flush NOW or
			// the FK from user_roles makes the role delete fail.
			entityManager.flush();
			if (ownRoleIds != null && !ownRoleIds.isEmpty()) {
				entityManager.createQuery(
						"DELETE FROM Role r WHERE r.id in :ids")
					.setParameter("ids", ownRoleIds)
					.executeUpdate();
			}
			entityManager.createQuery(
					"DELETE FROM Business b WHERE b.id = :bid")
					.setParameter("bid", BUSINESS_ID)
					.executeUpdate();
		});
	}

	/**
	 * The gate passes (RESOLVED line with a placeable id) but
	 * {@code createOrder} fails on stock (10 &gt; 5). The response is 500 — a
	 * DOCUMENTED decision (gap 56): the writer's error contract is not
	 * changed by this slice. The claim, however, is GONE: the proposal is
	 * {@code PENDING} with {@code order_id} NULL and the stock intact —
	 * retryable.
	 */
	@Test
	void aFailedWriterRollsBackTheClaim() throws Exception {

		Business business = businessRepository.findById(BUSINESS_ID).orElseThrow();
		Category category = new Category();
		category.setBusiness(business);
		category.setName("Rollback");
		category = categoryRepository.saveAndFlush(category);

		Product product = new Product();
		product.setCategory(category);
		product.setName("Producto escaso");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(5);
		product.setActive(true);
		product = productRepository.saveAndFlush(product);

		OrderProposal proposal = new OrderProposal();
		proposal.setBusiness(business);
		proposal.setStatus(ProposalStatus.PENDING);
		proposal.setChannel("whatsapp");
		proposal.setFromPhone(SUITE_FROM_PHONE);
		proposal.setCustomerName(null);
		proposal.setRawText("quiero 10 producto escaso");

		OrderProposalItem line = new OrderProposalItem();
		line.setProposal(proposal);
		line.setResolution(ItemResolution.RESOLVED);
		line.setProductId(product.getId());
		line.setProductName("Producto escaso");
		line.setQuantity(10);
		line.setRawLine("10 producto escaso");
		proposal.getItems().add(line);
		proposal = orderProposalRepository.saveAndFlush(proposal);

		String body = """
				{
				  "customerName": "Cliente Rollback",
				  "orderType": "RETIRO",
				  "paymentMethod": "EFECTIVO"
				}
				""";

		mockMvc.perform(post("/api/business/proposals/{id}/confirm", proposal.getId())
						.header("Authorization", "Bearer " + jwtUtil.generateToken(USERNAME, BUSINESS_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isInternalServerError());

		// Fresh reads, OUTSIDE any transaction that could have joined the
		// failed one: the claim rolled back with the writer.
		OrderProposal fresh = orderProposalRepository.findById(proposal.getId()).orElseThrow();
		assertThat(fresh.getStatus()).isEqualTo(ProposalStatus.PENDING);
		assertThat(fresh.getOrderId()).isNull();
		assertThat(productRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);

		Long ordersForThisBusiness = entityManager.createQuery(
					"select count(o) from Order o where o.business.id = :bid", Long.class)
				.setParameter("bid", BUSINESS_ID)
				.getSingleResult();
		assertThat(ordersForThisBusiness).isZero();
	}
}
