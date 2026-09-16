package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;

/**
 * Database integrity contract for {@code businesses.slug}.
 *
 * <p>The menu is addressed by slug ({@code /menu/index.html?restaurant=<slug>}
 * and {@code /api/orders/menu/{slug}}), and {@code BusinessRepository.findBySlug}
 * returns an {@code Optional}, which silently assumes at most one row per slug.
 * Only the database can actually guarantee it.</p>
 *
 * <p>Test 1 pins the UNIQUE constraint: a second business reusing a live slug
 * must be rejected by the database itself with PostgreSQL's
 * {@code unique_violation} (SQLState 23505). Test 2 pins the NOT NULL mapping:
 * a business without a slug must never be persisted.</p>
 *
 * <p>Manual ids: {@code businesses.id} has no generator (see
 * {@code PublicMenuOrderHttpTests}).</p>
 */
@SpringBootTest
@Transactional
class BusinessSlugIntegrityTests {

	private static final Long BASE_ID = 987_654_300L;

	private static final String DUPLICATE_SLUG = "slug-integrity-duplicate-probe";

	@Autowired
	private BusinessRepository businessRepository;

	private Business business(long id, String slug) {
		Business business = new Business();
		business.setId(id);
		business.setName("Slug Integrity Test Business " + id);
		business.setSlug(slug);
		return business;
	}

	@Test
	void databaseRejectsDuplicateBusinessSlug() {

		businessRepository.saveAndFlush(business(BASE_ID, DUPLICATE_SLUG));

		Business duplicate = business(BASE_ID + 1, DUPLICATE_SLUG);

		Throwable thrown = catchThrowable(() -> businessRepository.saveAndFlush(duplicate));

		assertThat(thrown)
				.as("a second business reusing an already-taken slug must be rejected, not silently accepted")
				.isNotNull();

		assertThat(thrown)
				.as("the rejection must come from the database constraint layer, not from application code")
				.hasRootCauseInstanceOf(SQLException.class);

		// Root-cause assertion above guarantees this is the database-level error.
		SQLException sql = sqlCauseOf(thrown);

		System.out.println("[slug-integrity] SQL error : " + sql.getMessage().trim());
		System.out.println("[slug-integrity] SQLState  : " + sql.getSQLState());
		System.out.println("[slug-integrity] ErrorCode : " + sql.getErrorCode());

		assertThat(sql.getSQLState())
				.as("PostgreSQL unique_violation")
				.isEqualTo("23505");
	}

	@Test
	void mappingRefusesNullSlug() {

		Throwable thrown = catchThrowable(() -> businessRepository.saveAndFlush(business(BASE_ID + 2, null)));

		assertThat(thrown)
				.as("a business without a slug must be rejected, not silently persisted")
				.isNotNull();

		System.out.println("[slug-integrity] null-slug rejection: "
				+ thrown.getClass().getName() + ": " + thrown.getMessage());

		assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
	}

	private SQLException sqlCauseOf(Throwable thrown) {
		Throwable current = thrown;
		while (current != null) {
			if (current instanceof SQLException sql) {
				return sql;
			}
			current = current.getCause();
		}
		return null;
	}
}
