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

/**
 * The ON/OFF switch the panel offers for a category has to mean something in
 * the carte: a deactivated category is not part of the public menu, so neither
 * it nor its products are offered.
 *
 * <p>The defect these tests pin: the public read path filtered products by
 * {@code products.active} but never looked at {@code categories.active}, so
 * turning a category off removed it from the panel's own list and changed
 * nothing a customer could see. The {@code categories} array of the payload is
 * asserted with the products so the menu never advertises a category it does
 * not offer.</p>
 *
 * <p>The sibling rule stays as it was: an inactive <em>product</em> in an
 * active category was already hidden and must keep being hidden.</p>
 */
@SpringBootTest
@Transactional
class InactiveCategoryNotOfferedTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_402L;

	private static final String SLUG = "inactive-category-menu-test-business";

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

	@Test
	void aDeactivatedCategoryAndItsProductsAreNotOfferedInTheMenu() {

		Business business = seedBusiness();

		Category bebidas = seedCategory(business, "Bebidas", 1, true);
		seedProduct(bebidas, "Agua mineral", "1800.00", true);

		Category promos = seedCategory(business, "Promos viejas", 2, false);
		seedProduct(promos, "Promo de verano", "9900.00", true);

		MenuDTO menu = menuService.getMenu(SLUG);

		assertThat(productNames(menu))
				.as("a category that is off cannot offer anything, even with active products inside")
				.containsExactly("Agua mineral");

		assertThat(categoryNames(menu))
				.as("the payload must not advertise a category the carte does not offer")
				.containsExactly("Bebidas");
	}

	/**
	 * The pre-existing half of the same idea, kept honest by the same fixture:
	 * an inactive product inside an active category stays out of the carte
	 * while the category itself is still offered.
	 */
	@Test
	void anInactiveProductInAnActiveCategoryKeepsBeingHidden() {

		Business business = seedBusiness();

		Category bebidas = seedCategory(business, "Bebidas", 1, true);
		seedProduct(bebidas, "Agua mineral", "1800.00", true);
		seedProduct(bebidas, "Limonada discontinuada", "1500.00", false);

		MenuDTO menu = menuService.getMenu(SLUG);

		assertThat(productNames(menu))
				.as("an inactive product stays hidden; its category keeps being offered")
				.containsExactly("Agua mineral");

		assertThat(categoryNames(menu))
				.as("the active category is still part of the carte")
				.containsExactly("Bebidas");
	}

	private static List<String> productNames(MenuDTO menu) {
		return menu.getProducts().stream().map(ProductDTO::getName).toList();
	}

	private static List<String> categoryNames(MenuDTO menu) {
		return menu.getCategories().stream().map(CategoryDTO::getName).toList();
	}

	private Business seedBusiness() {

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Inactive Category Test Business");
		business.setSlug(SLUG);
		business = businessRepository.saveAndFlush(business);
		BusinessAuthSeed.seedAlwaysOpenWeek(businessHoursRepository, businessRepository, business.getId());
		return business;
	}

	private Category seedCategory(Business business, String name, Integer orden, boolean active) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		category.setOrden(orden);
		category.setActive(active);
		return categoryRepository.saveAndFlush(category);
	}

	private Product seedProduct(Category category, String name, String price, boolean active) {

		Product product = new Product();
		product.setCategory(category);
		product.setName(name);
		product.setPrice(new BigDecimal(price));
		product.setCost(new BigDecimal("10.00"));
		product.setStock(10);
		product.setActive(active);
		return productRepository.saveAndFlush(product);
	}
}
