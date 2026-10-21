package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.util.List;

import com.carrito.saas.repository.enums.UnitMeasure;

import lombok.Data;

@Data
public class ProductDTO {

	private Long id;

	private String name;

	private Double trendPercentage;
	private String trendDirection;
	private ProductInsight productInsight;

	private String description;

	private BigDecimal price;
	private BigDecimal cost;

	private Boolean active;

	private Integer stock;

	private Long categoryId;

	private String categoryName;

	private String imageUrl;

	private UnitMeasure unitMeasure;

	/**
	 * Grupos de opciones definidos por el dueño (odd/tasks/product-options.md
	 * S1-S3). Llegan igual al menú público vía MenuDTO.products.
	 */
	private List<ProductOptionGroupDTO> optionGroups;

	public ProductDTO() {
		super();
	}

}
