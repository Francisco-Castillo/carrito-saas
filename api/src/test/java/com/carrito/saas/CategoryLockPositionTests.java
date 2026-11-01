package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.interfaces.ICategoryService;

/**
 * The serialization of the category writes is not "the alta takes a lock": it is
 * "the alta takes the lock BEFORE it reads the maximum". Taking it afterwards
 * leaves the exact race the lock exists to close — two creations read the same
 * {@code max(orden)} and only then queue — and every test that merely observes a
 * lock being taken would still pass.
 *
 * <p>{@code CategoryWriteSerializationTests} pins that the write WAITS for the
 * business row; this one pins the ORDER of the statements, which is the part a
 * concurrency test cannot see. It records every statement Hibernate sends to the
 * driver through a {@link StatementInspector} and requires the row lock to
 * appear before the maximum is read.</p>
 *
 * <p>No thread and no second connection: the assertion is on the sequence,
 * which makes it deterministic. A {@code @Transactional} test rolls the fixture
 * back.</p>
 */
@SpringBootTest(properties = {
		"spring.jpa.properties.hibernate.session_factory.statement_inspector="
				+ "com.carrito.saas.CategoryLockPositionTests$RecordingInspector" })
@Transactional
class CategoryLockPositionTests {

	/** Guarda, en orden, cada sentencia que Hibernate manda al driver. */
	public static class RecordingInspector implements StatementInspector {

		static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

		@Override
		public String inspect(String sql) {
			STATEMENTS.add(sql);
			return sql;
		}
	}

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_408L;

	private static final String SLUG = "category-lock-position-test";

	private static final String OWNER = "category-lock-position-owner";

	@Autowired
	private ICategoryService categoryService;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@BeforeEach
	void seedFixtures() {

		RecordingInspector.STATEMENTS.clear();

		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("Category Lock Position Business");
		business.setSlug(SLUG);
		businessRepository.saveAndFlush(business);

		categoryRepository.saveAndFlush(category(business, "Bebidas", 1));
		categoryRepository.saveAndFlush(category(business, "Postres", 2));

		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(OWNER, jwtUtil.generateToken(OWNER, BUSINESS_ID)));
	}

	/**
	 * El contexto de seguridad es thread-local: si queda seteado al terminar,
	 * otro test que comparta el hilo puede pasar por la razón equivocada
	 * (operando con el negocio de este fixture).
	 */
	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theAltaLocksTheBusinessRowBeforeItReadsTheMaximum() {

		CategoryDTO created = categoryService.crearCategoria(categoryDTO("Empanadas"));

		assertThat(created.getOrder()).as("the alta still appends last").isEqualTo(3);

		List<String> statements = new ArrayList<>(RecordingInspector.STATEMENTS);

		int lock = firstStatementWith(statements, "from businesses", "for no key update");
		int maximum = firstStatementWith(statements, "max(", "orden");

		assertThat(lock).as("the alta must take the business row for update").isGreaterThanOrEqualTo(0);
		assertThat(maximum).as("the alta must read max(orden)").isGreaterThanOrEqualTo(0);
		assertThat(lock)
				.as("the row lock must precede the read of the maximum: taken afterwards it leaves the race open")
				.isLessThan(maximum);
	}

	private static int firstStatementWith(List<String> statements, String... tokens) {

		for (int i = 0; i < statements.size(); i++) {
			String sql = statements.get(i).toLowerCase(Locale.ROOT);
			boolean matches = true;
			for (String token : tokens) {
				if (!sql.contains(token)) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return i;
			}
		}
		return -1;
	}

	private Category category(Business business, String name, Integer orden) {

		Category category = new Category();
		category.setBusiness(business);
		category.setName(name);
		category.setOrden(orden);
		category.setActive(true);
		return category;
	}

	private CategoryDTO categoryDTO(String name) {

		CategoryDTO dto = new CategoryDTO();
		dto.setName(name);
		dto.setActive(true);
		return dto;
	}
}
