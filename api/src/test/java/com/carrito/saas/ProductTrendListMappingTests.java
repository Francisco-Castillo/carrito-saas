package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.carrito.saas.repository.entity.ProductTrend;
import com.carrito.saas.repository.enums.TrendDirection;
import com.carrito.saas.service.mapper.impl.ProductTrendMapperImpl;

/**
 * Contract for {@link ProductTrendMapperImpl#toListDTO(List)}.
 *
 * <p>{@code toListDTO} used to be a TODO returning {@code null} while the
 * rest of the {@code ICarritoMapper} family honours it. It must map every
 * entity, in order, with exactly the same fields as calling {@code toDTO}
 * on each one, and an empty input must produce an empty list, not null.</p>
 */
class ProductTrendListMappingTests {

	private final ProductTrendMapperImpl mapper = new ProductTrendMapperImpl();

	private ProductTrend trend(String productName) {

		ProductTrend trend = new ProductTrend();
		trend.setProductId(1L);
		trend.setProductName(productName);
		trend.setBusinessId(987_900_002L);
		trend.setTrendPercentage(25.0);
		trend.setCurrentWeekSales(10);
		trend.setPreviousWeekSales(8);
		trend.setTrendDirection(TrendDirection.UP);
		trend.setUpdatedAt(LocalDateTime.of(2025, 1, 15, 10, 30));

		return trend;
	}

	/**
	 * {@code toListDTO} must map every entity, in order, with the same logic
	 * as {@code toDTO}: the result must equal the per-entity {@code toDTO}
	 * output (single source of truth, no hardcoded field values).
	 */
	@Test
	void toListDTOMapsEveryEntityInOrderLikeToDTO() {

		ProductTrend first = trend("Product Trend List First");
		ProductTrend second = trend("Product Trend List Second");

		List<com.carrito.saas.dto.ProductTrendDTO> dtos = mapper.toListDTO(List.of(first, second));

		assertThat(dtos)
				.as("toListDTO must not return null: it maps one DTO per entity")
				.isNotNull()
				.hasSize(2);
		assertThat(dtos.get(0)).isEqualTo(mapper.toDTO(first));
		assertThat(dtos.get(1)).isEqualTo(mapper.toDTO(second));
	}

	/** An empty input list must produce an empty list, not {@code null}. */
	@Test
	void toListDTOWithEmptyListReturnsEmptyList() {

		assertThat(mapper.toListDTO(List.of()))
				.isNotNull()
				.isEmpty();
	}

}
