package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Defense-in-depth tenant scope for the atomic stock writes.
 *
 * <p>The public order path no longer reaches a foreign product (the load is
 * business-scoped), but the atomic bulk writes {@code decrementStock} and
 * {@code incrementStock} are the last line of defense and must not depend on
 * the caller having loaded the product first: any future caller passing a
 * bare id must not be able to mutate another business's stock.</p>
 *
 * <p>Both writes must affect <strong>0 rows</strong> for a foreign productId
 * and leave the foreign stock exactly unchanged, while still working for an
 * own product: the decrement keeps its {@code stock >= :quantity} floor (no
 * overselling) and the increment restocks.</p>
 *
 * <p>Repository level, no HTTP. Bulk updates bypass the persistence context,
 * so every stock read-back happens after flushing and clearing the
 * {@link EntityManager} — a fresh SELECT against PostgreSQL, not a cached
 * in-memory value. {@code @Transactional} rolls back every fixture at test
 * end.</p>
 */
@SpringBootTest
@Transactional
class ProductStockTenantScopeTests {

	/** High fixed ids: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_A_ID = 987_700_201L;
	private static final Long BUSINESS_B_ID = 987_700_202L;

	private static final int STOCK_A = 10;
	private static final int STOCK_B = 5;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private Product ownProduct;
	private Product foreignProduct;

	@BeforeEach
	void seedFixtures() {

		Business businessA = new Business();
		businessA.setId(BUSINESS_A_ID);
		businessA.setName("Product Stock Scope A");
		businessA.setSlug("product-stock-scope-a");
		businessRepository.saveAndFlush(businessA);

		Business businessB = new Business();
		businessB.setId(BUSINESS_B_ID);
		businessB.setName("Product Stock Scope B");
		businessB.setSlug("product-stock-scope-b");
		businessRepository.saveAndFlush(businessB);

		Category categoryA = new Category();
		categoryA.setBusiness(businessA);
		categoryA.setName("Product Stock Scope Cat A");
		categoryA = categoryRepository.saveAndFlush(categoryA);

		Category categoryB = new Category();
		categoryB.setBusiness(businessB);
		categoryB.setName("Product Stock Scope Cat B");
		categoryB = categoryRepository.saveAndFlush(categoryB);

		Product own = new Product();
		own.setCategory(categoryA);
		own.setName("Product Stock Scope Product A");
		own.setPrice(new BigDecimal("100.00"));
		own.setCost(new BigDecimal("30.00"));
		own.setStock(STOCK_A);
		own.setActive(true);
		ownProduct = productRepository.saveAndFlush(own);

		Product foreign = new Product();
		foreign.setCategory(categoryB);
		foreign.setName("Product Stock Scope Product B");
		foreign.setPrice(new BigDecimal("55.00"));
		foreign.setCost(new BigDecimal("20.00"));
		foreign.setStock(STOCK_B);
		foreign.setActive(true);
		foreignProduct = productRepository.saveAndFlush(foreign);
	}

	private int stockOf(Product product) {

		return productRepository.findById(product.getId()).orElseThrow().getStock();
	}

	/**
	 * AC6: decrementStock with a foreign productId affects 0 rows and leaves
	 * the foreign stock exactly unchanged.
	 */
	@Test
	void decrementStockWithForeignProductIdAffectsZeroRowsAndLeavesStockUntouched() {

		int updatedRows = productRepository.decrementStock(foreignProduct.getId(), 1, BUSINESS_A_ID);

		assertThat(updatedRows)
				.as("a foreign productId must affect 0 rows")
				.isZero();

		entityManager.flush();
		entityManager.clear();

		assertThat(stockOf(foreignProduct))
				.as("B's stock must be exactly unchanged by a foreign decrement")
				.isEqualTo(STOCK_B);
		assertThat(stockOf(ownProduct))
				.as("A's own stock must be untouched by a foreign decrement")
				.isEqualTo(STOCK_A);
	}

	/**
	 * AC7: positive control. decrementStock with an own product still
	 * decrements and still respects the {@code stock >= :quantity} floor — no
	 * overselling.
	 */
	@Test
	void decrementStockWithOwnProductIdStillDecrementsAndNeverOversells() {

		assertThat(productRepository.decrementStock(ownProduct.getId(), 2, BUSINESS_A_ID))
				.as("an own productId must affect exactly 1 row")
				.isEqualTo(1);

		assertThat(productRepository.decrementStock(ownProduct.getId(), 999, BUSINESS_A_ID))
				.as("quantity above stock must affect 0 rows (stock >= :quantity guard)")
				.isZero();

		entityManager.flush();
		entityManager.clear();

		assertThat(stockOf(ownProduct))
				.as("A's own stock 10 - 2, and the failed 999-decrement must not touch it")
				.isEqualTo(STOCK_A - 2);
	}

	/**
	 * AC8: incrementStock with a foreign productId affects 0 rows and leaves
	 * the foreign stock exactly unchanged.
	 */
	@Test
	void incrementStockWithForeignProductIdAffectsZeroRowsAndLeavesStockUntouched() {

		int updatedRows = productRepository.incrementStock(foreignProduct.getId(), 3, BUSINESS_A_ID);

		assertThat(updatedRows)
				.as("a foreign productId must affect 0 rows")
				.isZero();

		entityManager.flush();
		entityManager.clear();

		assertThat(stockOf(foreignProduct))
				.as("B's stock must be exactly unchanged by a foreign increment")
				.isEqualTo(STOCK_B);
		assertThat(stockOf(ownProduct))
				.as("A's own stock must be untouched by a foreign increment")
				.isEqualTo(STOCK_A);
	}

	/**
	 * AC9: positive control. incrementStock with an own product still
	 * restocks after a decrement.
	 */
	@Test
	void incrementStockWithOwnProductIdStillRestocksAfterDecrement() {

		assertThat(productRepository.decrementStock(ownProduct.getId(), 4, BUSINESS_A_ID))
				.isEqualTo(1);

		assertThat(productRepository.incrementStock(ownProduct.getId(), 3, BUSINESS_A_ID))
				.as("an own restock must affect exactly 1 row")
				.isEqualTo(1);

		entityManager.flush();
		entityManager.clear();

		assertThat(stockOf(ownProduct))
				.as("A's own stock 10 - 4 + 3")
				.isEqualTo(STOCK_A - 4 + 3);
	}

}
