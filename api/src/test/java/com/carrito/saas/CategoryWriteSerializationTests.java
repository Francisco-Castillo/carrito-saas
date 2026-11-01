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
 * Every write that decides the order of the categories is a read-then-write:
 * the alta computes {@code max(orden) + 1}, and the edit and the reorder write
 * places they read from the list. Interleaved with another write they collide —
 * two creations land on the same {@code orden}, a reorder can duplicate a place
 * an in-flight alta just took — and the ordering query then breaks the tie by
 * id, which is deterministic but not the place the owner expected.
 *
 * <p>All three therefore take the business row for update before touching
 * {@code orden}, which serializes the category writes of that business. These
 * tests prove the serialization without relying on timing luck: each one holds
 * the row from its OWN connection in a mode the writers' foreign keys do not
 * already take, starts the write, and asserts the write does not complete while
 * the row is taken. It finishes as soon as the lock is released, which also
 * proves the wait ends with the real effect applied and not with a timeout.</p>
 *
 * <p>Deliberately NOT {@code @Transactional}: the whole point is two separate
 * transactions, so the fixtures are cleaned up by hand in
 * {@link #cleanUp()}.</p>
 */
@SpringBootTest
class CategoryWriteSerializationTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_700_406L;

	private static final String SLUG = "category-write-serialization-test";

	private static final String OWNER = "category-write-serialization-owner";

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

		CategoryDTO created = writeWaitingForTheBusinessRow(() -> categoryService.crearCategoria(categoryDTO("Empanadas")));

		assertThat(created).isNotNull();
		assertThat(created.getOrder()).as("it still appends after the last category").isEqualTo(3);
	}

	@Test
	void anEditWaitsWhileAnotherTransactionHoldsTheBusinessRow() throws Exception {

		CategoryDTO edited = writeWaitingForTheBusinessRow(
				() -> categoryService.actualizarCategoria(idOf("Bebidas"), dtoWithOrder("Bebidas", 9)));

		assertThat(edited).isNotNull();
		assertThat(edited.getOrder()).as("the edit applied its place").isEqualTo(9);
	}

	@Test
	void aReorderWaitsWhileAnotherTransactionHoldsTheBusinessRow() throws Exception {

		writeWaitingForTheBusinessRow(() -> {
			categoryService.ordenarCategorias(List.of(idOf("Postres"), idOf("Bebidas")));
			return null;
		});

		assertThat(categoryRepository.findByBusinessIdOrdered(BUSINESS_ID).stream().map(Category::getName).toList())
				.as("the reorder applied the new order")
				.containsExactly("Postres", "Bebidas");
	}

	/**
	 * Corre {@code write} en otro hilo mientras esta conexión sostiene la fila
	 * del negocio, y exige que la escritura NO termine mientras la fila está
	 * tomada: si terminara, habría tocado {@code orden} sin esperar a la otra
	 * transacción.
	 *
	 * <p>{@code FOR NO KEY UPDATE} y no {@code FOR UPDATE} por una razón
	 * concreta: el INSERT del alta ya toma {@code FOR KEY SHARE} sobre la fila
	 * del negocio por la FK, así que un {@code FOR UPDATE} del test bloquearía
	 * el alta incluso sin la serialización y el test no probaría nada.
	 * {@code FOR NO KEY UPDATE} es compatible con {@code FOR KEY SHARE} (la
	 * escritura sin serialización pasa derecho) e incompatible consigo mismo,
	 * que es el modo que toman las tres escrituras.</p>
	 */
	private <T> T writeWaitingForTheBusinessRow(java.util.function.Supplier<T> write) throws Exception {

		ExecutorService pool = Executors.newSingleThreadExecutor();
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch started = new CountDownLatch(1);

		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (Statement statement = holder.createStatement()) {
				statement.execute("SELECT id FROM businesses WHERE id = " + BUSINESS_ID + " FOR NO KEY UPDATE");
			}

			Future<?> task = pool.submit(() -> {
				// El contexto de seguridad es thread-local: el worker necesita el suyo.
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(OWNER, jwtUtil.generateToken(OWNER, BUSINESS_ID)));
				started.countDown();
				try {
					result.set(write.get());
				} catch (Throwable t) {
					failure.set(t);
				}
			});

			assertThat(started.await(5, TimeUnit.SECONDS)).as("the worker must reach the write").isTrue();

			// La ventana es generosa a propósito (una escritura local tarda
			// milisegundos), así que un verde no depende de un margen ajustado.
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
			while (!task.isDone() && System.nanoTime() < deadline) {
				Thread.sleep(25);
			}
			assertThat(task.isDone())
					.as("the write must wait for the business row instead of racing the order")
					.isFalse();

			holder.rollback();

			task.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).as("the write must end without an error once the row is free").isNull();
		return result.get();
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

	private Long idOf(String name) {
		return categoryRepository.findByBusinessIdOrdered(BUSINESS_ID).stream()
				.filter(c -> name.equals(c.getName()))
				.findFirst()
				.orElseThrow()
				.getId();
	}

	private CategoryDTO dtoWithOrder(String name, Integer order) {
		CategoryDTO dto = categoryDTO(name);
		dto.setOrder(order);
		return dto;
	}

	private CategoryDTO categoryDTO(String name) {

		CategoryDTO dto = new CategoryDTO();
		dto.setName(name);
		dto.setActive(true);
		return dto;
	}
}
