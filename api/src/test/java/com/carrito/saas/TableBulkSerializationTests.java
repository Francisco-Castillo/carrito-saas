package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.carrito.saas.dto.GenerateTablesRequestDTO;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.BusinessUserRepository;
import com.carrito.saas.repository.jpa.RoleRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.repository.jpa.UserRepository;
import com.carrito.saas.security.JwtUtil;
import com.carrito.saas.service.interfaces.ITableService;

/**
 * El alta por lote de mesas es un read-then-write: lee las mesas vivas del
 * local y asigna los números libres más bajos. Sin serialización, dos lotes
 * concurrentes del mismo negocio leen los mismos huecos y crean números
 * duplicados — y no puede existir un unique constraint en
 * {@code (business_id, table_number)} porque una mesa dada de baja conserva su
 * número mientras una nueva lo reutiliza.
 *
 * <p>{@code generateBulk} toma entonces la fila del negocio antes de leer, el
 * mismo lock que ya serializa la escritura de categorías (ver
 * {@code CategoryServiceImpl} y {@code CategoryWriteSerializationTests}). Este
 * test prueba la serialización sin depender de la suerte: sostiene la fila del
 * negocio desde SU PROPIA conexión en el modo que los writers usan
 * ({@code FOR NO KEY UPDATE}, compatible con el {@code FOR KEY SHARE} que la FK
 * ya toma en el INSERT, así que sin lock el lote pasa derecho), arranca el lote
 * en otro hilo y exige que NO termine mientras la fila está tomada. Al liberar
 * la fila el lote termina y persiste exactamente las mesas pedidas.</p>
 *
 * <p>Deliberadamente NOT {@code @Transactional}: la esencia son dos
 * transacciones separadas, así que los fixtures se limpian a mano en
 * {@link #cleanUp()}.</p>
 */
@SpringBootTest
class TableBulkSerializationTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_810_201L;

	private static final String SLUG = "table-bulk-serialization-test";

	private static final String OWNER = "table-bulk-serialization-owner";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private BusinessUserRepository businessUserRepository;

	@Autowired
	private TableRepository tableRepository;

	@Autowired
	private JwtUtil jwtUtil;

	@Autowired
	private ITableService tableService;

	private BusinessAuthSeed.AuthenticatedBusiness owner;

	@BeforeEach
	void seedFixtures() {

		SecurityContextHolder.clearContext();

		owner = BusinessAuthSeed.seedOwner(businessRepository, userRepository, roleRepository,
				businessUserRepository, jwtUtil, BUSINESS_ID, SLUG, OWNER);
	}

	@AfterEach
	void cleanUp() {

		SecurityContextHolder.clearContext();

		// Sin rollback automático: cada fila se borra a mano, hijos primero.
		tableRepository.deleteAll(tableRepository.findByBusinessId(BUSINESS_ID));
		businessUserRepository.findByUserUsername(OWNER).forEach(businessUserRepository::delete);
		userRepository.findByUsername(OWNER).ifPresent(userRepository::delete);
		businessRepository.deleteById(BUSINESS_ID);
	}

	@Test
	void aBulkWaitsWhileAnotherTransactionHoldsTheBusinessRow() throws Exception {

		ExecutorService pool = Executors.newSingleThreadExecutor();
		AtomicReference<List> result = new AtomicReference<>();
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
						new UsernamePasswordAuthenticationToken(OWNER, owner.bearerToken()));
				started.countDown();
				try {
					GenerateTablesRequestDTO request = new GenerateTablesRequestDTO();
					request.setQuantity(2);
					result.set((List) tableService.generateBulk(request));
				} catch (Throwable t) {
					failure.set(t);
				}
			});

			assertThat(started.await(5, TimeUnit.SECONDS)).as("el worker debe llegar al alta").isTrue();

			// La ventana es generosa a propósito (un lote local tarda
			// milisegundos), así que un verde no depende de un margen ajustado.
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
			while (!task.isDone() && System.nanoTime() < deadline) {
				Thread.sleep(25);
			}
			assertThat(task.isDone())
					.as("el lote debe esperar la fila del negocio en vez de competir por los números libres")
					.isFalse();

			holder.rollback();

			task.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).as("el lote debe terminar sin error una vez libre la fila").isNull();

		assertThat(result.get()).as("el lote debe crear exactamente las 2 mesas pedidas").hasSize(2);

		List<Integer> numbers = tableRepository.findByBusinessId(BUSINESS_ID).stream()
				.map(RestaurantTable::getTableNumber)
				.sorted()
				.toList();

		assertThat(numbers).as("los números deben ser los 2 libres más bajos").containsExactly(1, 2);
	}
}
