package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.jpa.BusinessHoursRepository;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IMenuService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The public menu must present the categories in the order the owner arranged
 * in the panel ({@code categories.orden}), and that order must be the SAME on
 * every read.
 *
 * <p>The defect these tests pin: the public read path asked PostgreSQL for
 * categories and products without an {@code ORDER BY}, so the order was
 * whatever the heap returned — usually insertion order, but any {@code UPDATE}
 * of a row (editing a price, a stock or a photo) rewrites it at the end of the
 * table and the category jumps in the carta. The legacy grouping in
 * {@code frontend/src/pages/Menu.tsx} groups products in payload order, so the
 * order of the {@code products} array IS the order of the carta; the
 * {@code categories} array is asserted with it so the payload never disagrees
 * with itself.</p>
 *
 * <p>Asserted through {@link IMenuService#getMenu(String)} — the same read path
 * the anonymous carta uses. {@code @Transactional} rolls back every fixture at
 * test end.</p>
 */
@SpringBootTest
@Transactional
class PublicMenuCategoryOrderTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_401L;

	private static final String SLUG = "public-menu-category-order-test-business";

	@Autowired
	private IMenuService menuService;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private BusinessHoursRepository businessHoursRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ProductRepository productRepository;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * The owner's order wins over the order in which the rows were created: a
	 * category named "Postres" created first with {@code orden = 2} belongs
	 * AFTER "Hamburguesas", created later with {@code orden = 1}.
	 */
	@Test
	void menuListsCategoriesAndProductsInTheOwnerOrderNotInInsertionOrder() {

		Business business = seedBusiness();

		// Insertion order is deliberately the reverse of the owner's order.
		Category postres = seedCategory(business, "Postres", 2);
		seedProduct(postres, "Flan casero", "3200.00");

		Category hamburguesas = seedCategory(business, "Hamburguesas", 1);
		seedProduct(hamburguesas, "Clasica", "7500.00");

		MenuDTO menu = menuService.getMenu(SLUG);

		assertThat(categoryNames(menu))
				.as("the categories array must follow the owner's order, not insertion order")
				.containsExactly("Hamburguesas", "Postres");

		assertThat(productCategoryNames(menu))
				.as("the carta groups products in payload order, so the products array must follow it too")
				.containsExactly("Hamburguesas", "Postres");
	}

	/**
	 * Two categories can share an {@code orden} (the panel computes the next
	 * value as {@code rowCount + 1}, so deleting a category in the middle is
	 * enough to create a tie). The tie must be broken by id — the older
	 * category first — so the carta cannot drift between two reads.
	 *
	 * <p>The fixture forces the mismatch that a live database produces on its
	 * own: an {@code UPDATE} rewrites the row at the end of the heap, so the
	 * physical order of the products no longer matches the id order.</p>
	 */
	@Test
	void aTiedOrderIsBrokenByCategoryIdSoTheCartaCannotDrift() {

		Business business = seedBusiness();

		Category zeta = seedCategory(business, "Zeta", 1);
		Product zetaProduct = seedProduct(zeta, "Producto de Zeta", "1000.00");

		Category alfa = seedCategory(business, "Alfa", 1);
		seedProduct(alfa, "Producto de Alfa", "2000.00");

		// The owner edits one product: PostgreSQL appends the new row version.
		zetaProduct.setDescription("Edicion del dueño");
		productRepository.saveAndFlush(zetaProduct);
		entityManager.clear();

		MenuDTO menu = menuService.getMenu(SLUG);

		assertThat(categoryNames(menu))
				.as("a tie on orden is broken by the older category id, whatever the storage order is")
				.containsExactly("Zeta", "Alfa");

		assertThat(productCategoryNames(menu))
				.as("editing a product must not move its category in the carta")
				.containsExactly("Zeta", "Alfa");
	}

	/**
	 * A category created without an {@code orden} (the POST accepts the field
	 * absent, and the column is nullable) has no place in the owner's list, so
	 * it goes after the ones that do — never before, and never in an arbitrary
	 * position. Same rule, boundary case.
	 */
	@Test
	void aCategoryWithoutOrderGoesAfterTheOrderedOnes() {

		Business business = seedBusiness();

		// La sin orden se crea primero: si el orden fuera el de inserción,
		// encabezaría la carta.
		Category sinOrden = seedCategory(business, "Sin orden", null);
		seedProduct(sinOrden, "Producto suelto", "500.00");

		Category bebidas = seedCategory(business, "Bebidas", 1);
		seedProduct(bebidas, "Agua mineral", "1800.00");

		MenuDTO menu = menuService.getMenu(SLUG);

		assertThat(productCategoryNames(menu))
				.as("a category without orden has no place in the list, so it goes last")
				.containsExactly("Bebidas", "Sin orden");
	}

	private static List<String> categoryNames(MenuDTO menu) {
		return menu.getCategories().stream().map(CategoryDTO::getName).toList();
	}

	private static List<String> productCategoryNames(MenuDTO menu) {
		return menu.getProducts().stream().map(ProductDTO::getCategoryName).toList();
	}

	private Business seedBusiness() {

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Carta Order Test Business");
		business.setSlug(SLUG);
		business = businessRepository.saveAndFlush(business);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, business.getId());
		return business;
	}

	private Category seedCategory(Business business, String name, Integer orden) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		category.setOrden(orden);
		category.setActive(true);
		return categoryRepository.saveAndFlush(category);
	}

	private Product seedProduct(Category category, String name, String price) {

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal(price));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(10);
		product.setActive(true);
		return productRepository.saveAndFlush(product);
	}
}
