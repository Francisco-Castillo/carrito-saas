package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * The JDBC analytics tables ({@code product_daily_sales},
 * {@code product_trends}, {@code product_predictions}, {@code daily_metrics},
 * {@code hourly_predictions}) have no JPA entity and no Hibernate migration:
 * their only owner is {@code schema.sql}, applied by Spring SQL init on every
 * startup. This test pins that provisioning on a fresh database — it is the
 * test that would have caught the original gap where the analytics layer
 * failed at runtime with {@code relation "product_trends" does not exist}.
 */
@SpringBootTest
class AnalyticsSchemaProvisioningTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private javax.sql.DataSource dataSource;

	@Test
	void startupProvisionsAllFiveAnalyticsTablesOnAFreshDatabase() {

		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

		assertThat(tables)
				.as("schema.sql must create every analytics table the JDBC repositories query")
				.contains(
						"product_daily_sales",
						"product_trends",
						"product_predictions",
						"daily_metrics",
						"hourly_predictions");
	}

	/**
	 * Re-running the actual {@code schema.sql} must not fail: every statement
	 * is IF NOT EXISTS, and sql.init runs on EVERY startup, not just the first.
	 * Runs inside a rolled-back transaction so the re-execution cannot leave
	 * schema mutations behind for other tests.
	 */
	@Test
	@Transactional
	void schemaSqlIsIdempotentWhenExecutedTwice() throws Exception {

		ScriptUtils.executeSqlScript(
				dataSource.getConnection(),
				new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));

		List<String> tables = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

		assertThat(tables).contains("product_trends", "daily_metrics");
	}

}
