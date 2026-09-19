package com.carrito.saas.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.Business;

/**
 * Data access for {@link Business}.
 *
 * <p>Phone lookup: {@code businesses.phone} is nullable and unconstrained,
 * and holds whatever the admin typed. Canonicalization to E.164 and the
 * comparison both happen in Java ({@code com.carrito.saas.service.whatsapp.PhoneNumbers}
 * with libphonenumber) — E.164 reasoning cannot be expressed in SQL, so the
 * former native {@code regexp_replace} query, its shared character-class
 * constant and the functional index that served it are gone. The resolver
 * loads the businesses whose phone is non-null through
 * {@link #findAllByPhoneIsNotNull()} and compares canonical forms in memory;
 * at the scale of one city that is correct and simple. Scaling to thousands
 * calls for a canonical column, which calls for a business write path that
 * does not exist yet.</p>
 *
 * <p>Phone lookup: {@code businesses.phone} stores canonical E.164 digits
 * without the {@code +} or NULL, enforced by the schema since T3c —
 * {@code CHECK (phone IS NULL OR phone ~ '^[1-9][0-9]{7,14}$')} plus
 * {@code UNIQUE (phone)}, applied by hand exactly as for {@code slug}
 * (annotation on the entity AND manual SQL, both documented in the
 * {@code Business.phone} javadoc, because the schema is not reproducible
 * from the repo — open gap 30). Canonicalization to E.164 and the comparison
 * both happen in Java ({@code com.carrito.saas.service.whatsapp.PhoneNumbers}
 * with libphonenumber) — E.164 reasoning cannot be expressed in SQL. The
 * in-memory canonicalization of the STORED side is kept as defence: it is
 * idempotent for the now-mandated canonical form and robust for any row that
 * predates the constraint. The resolver loads the businesses whose phone is
 * non-null through {@link #findAllByPhoneIsNotNull()} and delegates the
 * matching to the pure function {@code PhoneMatching}; at the scale of one
 * city that is correct and simple.</p>
 *
 * <p><strong>T3c supersedes the earlier deliberate absence of constraints</strong>
 * (T3/T3b): the product decision is settled (open gap 25) — each location has
 * its own WhatsApp number, and the policy lives where the data lives. The
 * resolver's ambiguous outcome survives as a layer of defence, tested against
 * the pure function because the database can no longer produce the state.</p>
 */
@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    Optional<Business> findBySlug(String slug);

    /**
     * Every business with a non-null phone, for in-memory E.164 matching by
     * the resolver via {@code PhoneMatching}. Since the T3c constraints, a
     * non-null value is canonical E.164 digits; the canonicalization on the
     * stored side remains as defence for pre-constraint rows, whose dirty
     * values canonicalize to nothing and match nothing.
     */
    List<Business> findAllByPhoneIsNotNull();

    @Query("SELECT b.id FROM Business b")
    List<Long> findAllIds();
}
