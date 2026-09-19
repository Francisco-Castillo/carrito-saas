package com.carrito.saas.repository.entity;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * The two UNIQUE constraints are declared here, on the table, with explicit
 * names rather than as {@code @Column(unique = true)} on each field.
 *
 * <p><strong>Why the names matter.</strong> A generated name is
 * environment-dependent: a database created by hand got
 * {@code uq_businesses_phone}, while a database whose schema {@code ddl-auto}
 * generates from this entity used to get something like
 * {@code uk8140cl0n9nxy70j919keu3mmj}. The definitions were identical, so
 * nothing was broken — but the SAME schema had different names per
 * environment, and a future migration that says
 * {@code DROP CONSTRAINT uq_businesses_phone} would have failed on any
 * environment that had not been hand-altered. Declaring the name here makes
 * fresh environments agree with existing ones, which is what makes the names
 * referencable from a migration later (see open gap 31).</p>
 *
 * <p>Note that {@code unique = true} must NOT be kept on the fields as well:
 * that would declare two unique constraints per column.</p>
 */
@Data
@Entity
@Table(name = "businesses",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_businesses_slug", columnNames = "slug"),
                @UniqueConstraint(name = "uq_businesses_phone", columnNames = "phone")})
public class Business {

    @Id
    private Long id;

    private String name;

    /**
     * Public identity of the business: the menu is addressed by it
     * ({@code /menu/index.html?restaurant=<slug>}, {@code /api/orders/menu/{slug}})
     * and {@code BusinessRepository.findBySlug} assumes at most one row per
     * value, so it is UNIQUE and NOT NULL.
     *
     * <p>Runtime NOT NULL enforcement relies on
     * {@code spring.jpa.properties.hibernate.check_nullability: true} in
     * {@code application.yml}: Hibernate skips its own nullability check when a
     * Bean Validation provider is on the classpath, and this module has no
     * {@code jakarta.validation} dependency to declare {@code @NotNull}.</p>
     */
    @Column(nullable = false)
    private String slug;

    /**
     * The business's own WhatsApp number, stored as <strong>canonical E.164
     * digits without the leading {@code +}</strong> (e.g.
     * {@code 5491122334455}) or {@code NULL}. This is the number Meta sends
     * as {@code display_phone_number} and the one the public menu uses for
     * its {@code wa.me} fallback.
     *
     * <p><strong>T3c — the schema enforces "each location has its own
     * number".</strong> The policy is decided (open gap 25): two businesses
     * may not share a WhatsApp number. A plain {@code UNIQUE} on a free-text
     * column does NOT enforce it, because {@code 011 2233-4455} and
     * {@code +54 9 11 2233-4455} are different strings for the SAME
     * subscriber — the equality the resolver uses is E.164, which is not
     * expressible as a SQL constraint ({@code regexp_replace} strips
     * decoration but cannot interpret a number). The constraint only enforces
     * the policy because <em>what is stored is what is compared</em>: the
     * canonical digits form, also the input of
     * {@code com.carrito.saas.service.whatsapp.PhoneNumbers#canonicalizeToE164}
     * on the stored side (idempotent for already-canonical values).
     *
     * <p><strong>Reproducible schema (T3c, CI-parity fix):</strong> the CHECK
     * is declared on the entity via {@code @Column(check = @CheckConstraint)}
     * (Jakarta Persistence 3.2 — the classpath ships
     * {@code jakarta.persistence-api 3.2.0}, processed by Hibernate ORM
     * 7.2.4's model layer), so {@code ddl-auto} emits it when CREATING the
     * table — any fresh environment (CI included) starts with the constraint
     * in place. There is no JPA annotation for check constraints before
     * Persistence 3.2; Hibernate's own {@code org.hibernate.annotations.Check}
     * would also work but is deprecated since Hibernate 7.
     *
     * <p><strong>Existing databases still need the manual SQL — the
     * annotation alone is not the migration.</strong> There is no
     * Flyway/Liquibase and {@code ddl-auto: update} does not reliably alter
     * an existing table, so — exactly as for {@link #slug} — the dev database
     * was altered manually with this exact SQL (applied 2026-09-19, T3c):
     *
     * <pre>
     * ALTER TABLE businesses ADD CONSTRAINT businesses_phone_shape
     *     CHECK (phone IS NULL OR phone ~ '^[1-9][0-9]{7,14}$');
     * ALTER TABLE businesses ADD CONSTRAINT uq_businesses_phone UNIQUE (phone);
     * </pre>
     *
     * <p>Postgres treats NULLs as distinct in a unique index, so a business
     * without a WhatsApp number remains possible and NULLs do not collide.
     * The project's schema is NOT fully reproducible from the repository
     * (open gap 30) for databases that ALREADY exist; if you create one by
     * hand from a pre-T3c schema, apply the SQL above.
     *
     * <p><strong>Obligation for the future business create/update path (not
     * built yet — {@code BusinessController} has no write operations):</strong>
     * it MUST canonicalize the user-supplied value through
     * {@code PhoneNumbers#canonicalizeToE164} and store the result without
     * the leading {@code +}; a value that does not canonicalize must be
     * rejected, not stored raw — the CHECK would reject it anyway, but the
     * error should name the reason. Write-time validation in code is
     * deliberately absent in this slice: the CHECK is the enforcement.</p>
     */
    @Column(check = @CheckConstraint(name = "businesses_phone_shape",
            constraint = "phone IS NULL OR phone ~ '^[1-9][0-9]{7,14}$'"))
    private String phone;

}