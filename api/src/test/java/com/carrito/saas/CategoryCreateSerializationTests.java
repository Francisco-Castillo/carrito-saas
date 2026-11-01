package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Category;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.CategoryRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.interfaces.ICategoryService;

/**
 * Creating a category appends it at the end, which means tearing the place out
 * of {@code max(orden)} and writing {@code max + 1}: a read-then-write. Two
 * creations that interleave read the same maximum and land on the same
 * {@code orden}, so the second one ties with the first instead of following it
 * (the ordering query then breaks the tie by id, which is deterministic but not
 * the place the owner expected).
 *
 * <p>The alta must therefore be serialized per business: it takes the business
 * row for update before computing the place. This test proves the serialization
 * without relying on timing luck — it holds that row from its OWN connection in
 * a mode the alta's foreign key does not already take, starts the creation, and
 * asserts the creation does not complete while the row is taken. It finishes as
 * soon as the lock is released, which also proves the wait ends with a real
 * category and not with a timeout.</p>
 *
 * <p>Deliberately NOT {@code @Transactional}: the whole point is two separate
 * transactions, so the fixtures are cleaned up by hand in
 * {@link #cleanUp()}.</p>
 */
@SpringBootTest
class CategoryCreateSerializationTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_406L;

	private static final String SLUG = "category-create-serialization-test";

	private static final String OWNER = "category-create-serialization-owner";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private ICategoryService categoryService;

	@Autowired
	private JwtUtil jwtUtil;

	private Business business;

	@BeforeEach
	void seedFixtures() {

		SecurityContextHolder.clearContext();

		Business seeded = new Business();
		seeded.setId(BUSINESS_ID);
		seeded.setName("Category Create Serialization Business");
		seeded.setSlug(SLUG);
		business = businessRepository.saveAndFlush(seeded);

		categoryRepository.saveAndFlush(category("Bebidas", 1));
		categoryRepository.saveAndFlush(category("Postres", 2));
	}

	@AfterEach
	void cleanUp() {

		SecurityContextHolder.clearContext();

		List<Category> rows = categoryRepository.findByBusinessIdOrdered(BUSINESS_ID);
		categoryRepository.deleteAll(rows);
		if (business != null) {
			businessRepository.deleteById(BUSINESS_ID);
		}
	}

	@Test
	void aCreationWaitsWhileAnotherTransactionHoldsTheBusinessRow() throws Exception {

		ExecutorService pool = Executors.newSingleThreadExecutor();
		AtomicReference<CategoryDTO> created = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch started = new CountDownLatch(1);

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (Statement statement = holder.createStatement()) {
				// OJO con el modo de lock: el INSERT del alta ya toma FOR KEY SHARE
				// sobre la fila del negocio por la FK, así que un FOR UPDATE acá
				// bloquearía el alta incluso sin el fix y el test no probaría nada.
				// FOR NO KEY UPDATE es compatible con FOR KEY SHARE (el alta sin
				// serialización pasa derecho) e incompatible con el FOR NO KEY
				// UPDATE que el alta toma para calcular su lugar — Hibernate
				// renderiza así PESSIMISTIC_WRITE en PostgreSQL, y ese modo se
				// bloquea consigo mismo.
				statement.execute("SELECT id FROM businesses WHERE id = " + BUSINESS_ID + " FOR NO KEY UPDATE");
			}

			Future<?> creation = pool.submit(() -> {
				// El contexto de seguridad es thread-local: el worker necesita el suyo.
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(OWNER, jwtUtil.generateToken(OWNER, BUSINESS_ID)));
				started.countDown();
				try {
					created.set(categoryService.crearCategoria(categoryDTO("Empanadas")));
				} catch (Throwable t) {
					failure.set(t);
				}
			});

			assertThat(started.await(5, TimeUnit.SECONDS)).as("the worker must reach the creation").isTrue();

			// Con la fila del negocio tomada, el alta no puede terminar: si
			// terminara, habría leído el máximo sin esperar a la otra
			// transacción y podría empatar. La ventana es generosa a propósito
			// (un alta local tarda milisegundos), así que un verde no depende de
			// un margen ajustado.
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
			while (!creation.isDone() && System.nanoTime() < deadline) {
				Thread.sleep(25);
			}
			assertThat(creation.isDone())
					.as("the creation must wait for the business row instead of racing the max")
					.isFalse();

			holder.rollback();

			creation.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).as("the creation must end without an error once the row is free").isNull();

		CategoryDTO result = created.get();
		assertThat(result).isNotNull();
		assertThat(result.getOrder()).as("it still appends after the last category").isEqualTo(3);
	}

	/**
	 * El camino que ahora toma un lock no puede cambiar su contrato de error: un
	 * token con un negocio inexistente sigue siendo "Local no encontrado" (el
	 * lock no encuentra fila, así que no espera, y la transacción se descarta).
	 */
	@Test
	void aTokenForANonExistentBusinessStillAnswersNotFound() {

		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(OWNER, jwtUtil.generateToken(OWNER, 999_999_998L)));

		assertThatExceptionOfType(BusinessException.class)
				.isThrownBy(() -> categoryService.crearCategoria(categoryDTO("Fantasma")))
				.withMessage("Local no encontrado");
	}

	private Category category(String name, Integer orden) {

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
