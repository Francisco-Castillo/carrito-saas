package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;

/**
 * Tenant-scope contract of the ORDER-PATH READS, at repository level (no HTTP).
 *
 * <p>Two layers defend the public order channel: the product load is
 * business-scoped (this class) and the atomic stock writes are business-scoped
 * (ProductStockTenantScopeTests). An independent verifier proved by mutation
 * that the write layer alone makes the HTTP test pass, so the load predicate
 * and the combo predicate had no committed coverage: this class fixes
 * AC4 and AC5.</p>
 *
 * <p>Every assertion here must FAIL when its tenant predicate is reverted —
 * that mutation sensitivity is the acceptance criterion, not a green run.</p>
 *
 * <p>Repository level, no HTTP. {@code @Transactional} rolls back every
 * fixture at test end; high fixed {@code businesses.id} values keep fixtures
 * from colliding (the id is manually assigned, no generator). {@code Combo}
 * declares {@code category_id nullable = false}, so a combo fixture must be
 * attached to a business-owned category, and its component product must live
 * in one too.</p>
 */
@SpringBootTest
@Transactional
class TenantScopedQueryContractTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_700_301L;
	private static final Long BUSINESS_B_ID = 987_700_302L;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ComboRepository comboRepository;

	private Product ownProduct;
	private Product foreignProduct;
	private Combo ownCombo;

	@BeforeEach
	void seedFixtures() {

		Business businessA = new Business();
		businessA.setId(BUSINESS_A_ID);
		businessA.setName("Query Contract A");
		businessA.setSlug("query-contract-a");
		businessRepository.saveAndFlush(businessA);

		Business businessB = new Business();
		businessB.setId(BUSINESS_B_ID);
		businessB.setName("Query Contract B");
		businessB.setSlug("query-contract-b");
		businessRepository.saveAndFlush(businessB);

		Category categoryA = new Category();
		categoryA.setBusiness(businessA);
		categoryA.setName("Query Contract Cat A");
		categoryA = categoryRepository.saveAndFlush(categoryA);

		Category categoryB = new Category();
		categoryB.setBusiness(businessB);
		categoryB.setName("Query Contract Cat B");
		categoryB = categoryRepository.saveAndFlush(categoryB);

		Product own = new Product();
		own.setCategory(categoryA);
		own.setName("Query Contract Product A");
		own.setPrice(new BigDecimal("100.00"));
		own.setCost(new BigDecimal("30.00"));
		own.setStock(10);
		own.setActive(true);
		ownProduct = productRepository.saveAndFlush(own);

		Product foreign = new Product();
		foreign.setCategory(categoryB);
		foreign.setName("Query Contract Product B");
		foreign.setPrice(new BigDecimal("55.00"));
		foreign.setCost(new BigDecimal("20.00"));
		foreign.setStock(7);
		foreign.setActive(true);
		foreignProduct = productRepository.saveAndFlush(foreign);

		// Combo owned by A: category nullable=false, one component of A.
		Combo combo = new Combo();
		combo.setName("Query Contract Combo A");
		combo.setPrice(new BigDecimal("120.00"));
		combo.setCategory(categoryA);

		ComboProduct comboItem = new ComboProduct();
		comboItem.setCombo(combo);
		comboItem.setProduct(own);
		comboItem.setQuantity(new BigDecimal("1"));

		combo.setItems(List.of(comboItem));
		ownCombo = comboRepository.saveAndFlush(combo);
	}

	/**
	 * AC4: the pessimistic order-path load is business-scoped. With both ids
	 * requested, exactly the OWN product comes back — the positive control
	 * (own product present) is what keeps this test from passing on an empty
	 * result, and the foreign exclusion is what the reverted predicate breaks.
	 */
	@Test
	void findAllByIdInForUpdateReturnsOwnProductAndExcludesForeignProduct() {

		List<Product> loaded = productRepository.findAllByIdInForUpdate(
				List.of(ownProduct.getId(), foreignProduct.getId()), BUSINESS_A_ID);

		assertThat(loaded)
				.as("positive control: A's own product must be returned under lock")
				.extracting(Product::getId)
				.containsExactly(ownProduct.getId());

		assertThat(loaded)
				.as("the foreign product must not enter the order-path load map")
				.extracting(Product::getId)
				.doesNotContain(foreignProduct.getId());
	}

	/**
	 * AC4: a load request consisting ONLY of a foreign id returns nothing —
	 * this is the exact call shape the anonymous public channel receives when
	 * an attacker sends a foreign productId.
	 */
	@Test
	void findAllByIdInForUpdateWithOnlyForeignIdReturnsEmpty() {

		List<Product> loaded = productRepository.findAllByIdInForUpdate(
				List.of(foreignProduct.getId()), BUSINESS_A_ID);

		assertThat(loaded)
				.as("a foreign-only load must return empty for business A")
				.isEmpty();
	}

	/**
	 * AC5: findFullMenuCombosByIds with the WRONG business returns empty.
	 * Fails when the combo tenant predicate is reverted.
	 */
	@Test
	void findFullMenuCombosByIdsWithWrongBusinessReturnsEmpty() {

		List<Combo> loaded = comboRepository.findFullMenuCombosByIds(
				List.of(ownCombo.getId()), BUSINESS_B_ID);

		// Extract ids before asserting: Combo has a bidirectional Lombok @Data
		// relation with ComboProduct, and rendering whole entities on failure
		// would overflow the stack instead of showing the diff.
		assertThat(loaded)
				.as("a combo of A must not be resolvable through B's menu lookup")
				.extracting(Combo::getId)
				.isEmpty();
	}

	/**
	 * AC5 positive control: the same combo IS returned for its own business,
	 * with its component product fetched — the lookup is not vacuously empty.
	 */
	@Test
	void findFullMenuCombosByIdsWithOwnBusinessReturnsComboWithComponents() {

		List<Combo> loaded = comboRepository.findFullMenuCombosByIds(
				List.of(ownCombo.getId()), BUSINESS_A_ID);

		assertThat(loaded)
				.as("positive control: A must resolve its own combo with items")
				.singleElement()
				.satisfies(combo -> {
					assertThat(combo.getId()).isEqualTo(ownCombo.getId());
					assertThat(combo.getItems())
							.as("the combo must carry its component product")
							.singleElement()
							.satisfies(item -> assertThat(item.getProduct().getId())
									.isEqualTo(ownProduct.getId()));
				});
	}

}
