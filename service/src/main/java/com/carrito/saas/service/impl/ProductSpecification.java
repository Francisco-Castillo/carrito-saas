package com.carrito.saas.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.carrito.saas.dto.ProductFilterDTO;
import com.carrito.saas.repository.entity.Product;

import jakarta.persistence.criteria.Predicate;

public class ProductSpecification {
	
	 public static Specification<Product> byRestaurant(Long restaurantId) {
	        return (root, query, cb) ->
	                cb.equal(root.get("category").get("business").get("id"), restaurantId);
	    }

	    public static Specification<Product> isActive() {
	        return (root, query, cb) ->
	                cb.isTrue(root.get("active"));
	    }

	    public static Specification<Product> hasStock() {
	        return (root, query, cb) ->
	                cb.or(
	                        cb.isNull(root.get("stock")),
	                        cb.greaterThan(root.get("stock"), 0)
	                );
	    }

	    public static Specification<Product> withFilters(ProductFilterDTO filter) {
	        return (root, query, cb) -> {

	            List<Predicate> predicates = new ArrayList<>();

	            if (filter.getCategoryId() != null) {
	                predicates.add(cb.equal(root.get("category").get("id"), filter.getCategoryId()));
	            }

	            if (filter.getMinPrice() != null) {
	                predicates.add(cb.greaterThanOrEqualTo(root.get("price"), filter.getMinPrice()));
	            }

	            if (filter.getMaxPrice() != null) {
	                predicates.add(cb.lessThanOrEqualTo(root.get("price"), filter.getMaxPrice()));
	            }

	            if (filter.getSearch() != null && !filter.getSearch().isEmpty()) {
	                String like = "%" + filter.getSearch().toLowerCase() + "%";

	                Predicate nameMatch = cb.like(cb.lower(root.get("name")), like);
	                Predicate categoryMatch = cb.like(
	                        cb.lower(root.get("category").get("name")), like
	                );

	                predicates.add(cb.or(nameMatch, categoryMatch));
	            }

	            return cb.and(predicates.toArray(new Predicate[0]));
	        };
	    }

}
