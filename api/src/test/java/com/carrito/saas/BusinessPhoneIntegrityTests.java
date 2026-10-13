package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;

/**
 * Database integrity contract for {@code businesses.phone}.
 *
 * <p>{@code businesses.phone} is nullable and holds the canonical E.164 digits
 * without the leading {@code +}, or {@code NULL}. Two constraints pin that
 * shape and the "each location has its own number" policy, and only the
 * database can enforce them: the CHECK ({@code businesses_phone_shape}) and
 * the named UNIQUE ({@code uq_businesses_phone}). Both live in the entity
 * annotations and the schema is {@code ddl-auto} output, with no migrations in
 * the repository, so without these tests either one can disappear silently.</p>
 *
 * <p>Test 1 pins the UNIQUE: a second business reusing a live phone must be
 * rejected by the database itself with PostgreSQL's {@code unique_violation}
 * (SQLState 23505). Test 2 pins the CHECK: the decorated local form an admin
 * would type ({@code 011 2233-4455}) must be rejected with
 * {@code check_violation} (SQLState 23514) — the constraint only enforces the
 * policy because what is stored is what is compared, so a decorated string
 * would be a different value for the same subscriber. Test 3 pins that the
 * UNIQUE does not turn a nullable column into an effective NOT NULL: two
 * businesses without a phone both persist. Test 4 is the positive control, so
 * the suite cannot pass vacuously against a schema that rejects everything.</p>
 *
 * <p>Manual ids: {@code businesses.id} has no generator (see
 * {@code PublicMenuOrderHttpTests}), and {@code businesses.slug} is NOT NULL and
 * UNIQUE, so every fixture needs its own slug.</p>
 */
@SpringBootTest
@Transactional
class BusinessPhoneIntegrityTests {

	private static final Long BASE_ID = 987_654_400L;

	private static final String CANONICAL_PHONE = "5491122334455";

	private static final String DUPLICATE_PHONE = "5491122334466";

	/** The decorated local spelling the javadoc forbids: same subscriber, different string. */
	private static final String DECORATED_LOCAL_PHONE = "011 2233-4455";

	@Autowired
	private BusinessRepository businessRepository;

	private Business business(long id, String slug, String phone) {
		Business business = new Business();
		business.setId(id);
		business.setName("Phone Integrity Test Business " + id);
		business.setSlug(slug);
		business.setPhone(phone);
		return business;
	}

	@Test
	void databaseRejectsDuplicateBusinessPhone() {

		businessRepository.saveAndFlush(business(BASE_ID, "phone-integrity-duplicate-a", DUPLICATE_PHONE));

		Business duplicate = business(BASE_ID + 1, "phone-integrity-duplicate-b", DUPLICATE_PHONE);

		Throwable thrown = catchThrowable(() -> businessRepository.saveAndFlush(duplicate));

		assertThat(thrown)
				.as("a second business reusing an already-taken phone must be rejected, not silently accepted")
				.isNotNull();

		assertThat(thrown)
				.as("the rejection must come from the database constraint layer, not from application code")
				.hasRootCauseInstanceOf(SQLException.class);

		SQLException sql = sqlCauseOf(thrown);

		System.out.println("[phone-integrity] SQL error : " + sql.getMessage().trim());
		System.out.println("[phone-integrity] SQLState  : " + sql.getSQLState());
		System.out.println("[phone-integrity] ErrorCode : " + sql.getErrorCode());

		assertThat(sql.getSQLState())
				.as("PostgreSQL unique_violation")
				.isEqualTo("23505");
	}

	@Test
	void databaseRejectsNonCanonicalPhoneShape() {

		Business decorated = business(BASE_ID + 2, "phone-integrity-shape", DECORATED_LOCAL_PHONE);

		Throwable thrown = catchThrowable(() -> businessRepository.saveAndFlush(decorated));

		assertThat(thrown)
				.as("a decorated local phone must be rejected: storing it would defeat the UNIQUE, because it is a "
						+ "different string for the same subscriber")
				.isNotNull();

		assertThat(thrown)
				.as("the rejection must come from the database constraint layer, not from application code")
				.hasRootCauseInstanceOf(SQLException.class);

		SQLException sql = sqlCauseOf(thrown);

		System.out.println("[phone-integrity] SQL error : " + sql.getMessage().trim());
		System.out.println("[phone-integrity] SQLState  : " + sql.getSQLState());
		System.out.println("[phone-integrity] ErrorCode : " + sql.getErrorCode());

		assertThat(sql.getSQLState())
				.as("PostgreSQL check_violation")
				.isEqualTo("23514");
	}

	@Test
	void nullPhonesDoNotCollide() {

		businessRepository.saveAndFlush(business(BASE_ID + 3, "phone-integrity-null-a", null));
		businessRepository.saveAndFlush(business(BASE_ID + 4, "phone-integrity-null-b", null));

		assertThat(businessRepository.findById(BASE_ID + 3))
				.as("a business without a phone must remain possible")
				.isPresent();

		assertThat(businessRepository.findById(BASE_ID + 4))
				.as("PostgreSQL treats NULLs as distinct in a unique index, so a second NULL does not collide")
				.isPresent();
	}

	@Test
	void canonicalPhoneIsAccepted() {

		Business business = business(BASE_ID + 5, "phone-integrity-canonical", CANONICAL_PHONE);

		businessRepository.saveAndFlush(business);

		assertThat(businessRepository.findById(BASE_ID + 5))
				.as("the canonical digits form must be accepted and stored as-is")
				.isPresent()
				.get()
				.extracting(Business::getPhone)
				.isEqualTo(CANONICAL_PHONE);
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
