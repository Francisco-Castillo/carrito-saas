package com.carrito.saas;

import jakarta.persistence.EntityManager;

/**
 * Test isolation for the SLUGLESS single-business contract tests: those
 * endpoints fail closed unless the deployment holds EXACTLY one business, so
 * the fixture must guarantee it against a shared dev database that keeps seed
 * rows (demo-e2e) from other sessions.
 *
 * <p>{@code TRUNCATE businesses CASCADE} does that in one statement but is
 * broader than the contract needs (it also wipes unrelated tables through FK
 * chains). {@link #keepOnlyBusiness} instead deletes every row of the OTHER
 * businesses and their dependents in FK-safe order — the same sequence the
 * production teardown uses — inside the test's transaction, so the rollback
 * restores everything.</p>
 */
final class SingleBusinessTestIsolation {

	private SingleBusinessTestIsolation() {
	}

	/**
	 * Deletes every business except {@code keepBusinessId}, with all of their
	 * dependent rows, in FK-safe order. Must run inside the test transaction.
	 */
	static void keepOnlyBusiness(EntityManager entityManager, long keepBusinessId) {

		// Dependents of orders first (order_items references both orders and
		// combos of other businesses), then the business-wide tables, then the
		// catalog, then the businesses themselves.
		entityManager.createNativeQuery("""
				delete from order_items where order_id in
					(select id from orders where business_id <> :b)
					or combo_id in
					(select cb.id from combos cb join categories cg on cb.category_id = cg.id
					 where cg.business_id <> :b)
				""".replace(":b", String.valueOf(keepBusinessId))).executeUpdate();

		String[] simpleDeletes = {
				"delete from table_request where business_id <> %d",
				// order_proposals se quitó de acá: el revert d16d37a eliminó el
				// modelo OrderProposal, así que en una DB fresca (CI) la tabla no
				// existe y este delete rompía el CI con SQLGrammarException.
				"delete from orders where business_id <> %d",
				"delete from restaurant_table where business_id <> %d",
				"delete from business_date_exception where business_id <> %d",
				"delete from business_hours where business_id <> %d",
				"delete from business_settings where business_id <> %d",
				"delete from business_users where business_id <> %d",
		};
		for (String template : simpleDeletes) {
			entityManager.createNativeQuery(template.formatted(keepBusinessId)).executeUpdate();
		}

		entityManager.createNativeQuery("""
				delete from combo_products where combo_id in
					(select cb.id from combos cb join categories cg on cb.category_id = cg.id
					 where cg.business_id <> %d)
					or producto_id in
					(select p.id from products p join categories cg on p.category_id = cg.id
					 where cg.business_id <> %d)
				""".formatted(keepBusinessId, keepBusinessId)).executeUpdate();

		entityManager.createNativeQuery("""
				delete from combos where category_id in
					(select id from categories where business_id <> %d)
				""".formatted(keepBusinessId)).executeUpdate();

		entityManager.createNativeQuery("""
				delete from product_options where group_id in
					(select g.id from product_option_groups g
					 join products p on g.product_id = p.id
					 join categories cg on p.category_id = cg.id
					 where cg.business_id <> %d)
				""".formatted(keepBusinessId)).executeUpdate();

		// product_options/product_option_groups (feature product-options) hang
		// off products with a NOT NULL FK: without these deletes the products
		// delete below violates fk on any database that holds option groups of
		// other businesses (the shared dev database keeps the demo's rows).
		entityManager.createNativeQuery("""
				delete from product_option_groups where product_id in
					(select p.id from products p join categories cg on p.category_id = cg.id
					 where cg.business_id <> %d)
				""".formatted(keepBusinessId)).executeUpdate();

		entityManager.createNativeQuery("""
				delete from products where category_id in
					(select id from categories where business_id <> %d)
				""".formatted(keepBusinessId)).executeUpdate();

		entityManager.createNativeQuery("delete from categories where business_id <> %d"
				.formatted(keepBusinessId)).executeUpdate();

		entityManager.createNativeQuery("delete from businesses where id <> %d"
				.formatted(keepBusinessId)).executeUpdate();
	}
}
