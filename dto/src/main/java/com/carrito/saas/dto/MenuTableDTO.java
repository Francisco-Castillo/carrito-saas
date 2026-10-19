package com.carrito.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The ONLY public identity of a restaurant table, served anonymously by
 * {@code GET /api/menu/table/{id}} so the customer menu can show a badge
 * when the scan of a per-table QR opened the page with {@code ?table=<id>}.
 *
 * <p>Deliberately minimal: no {@code qrToken}, no {@code qrUrl}, no status —
 * the anonymous surface must not leak anything beyond the name a customer
 * already sees printed on the table.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuTableDTO {

	private Long id;

	private Integer tableNumber;

	private String tableName;
}
