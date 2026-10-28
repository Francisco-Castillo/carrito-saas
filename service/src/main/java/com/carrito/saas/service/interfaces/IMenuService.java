package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.MenuTableDTO;

public interface IMenuService {

	MenuDTO getMenu(String slug);

	/**
	 * Menú público con sucursal pedida (multi-sucursal,
	 * odd/tasks/multi-sucursal.md, S4/T9): hours y whatsapp reflejan la
	 * sucursal {@code branchId} cuando es propia y activa; inválida/ajena/
	 * inactiva o null → la default (fallback silencioso: es display público,
	 * los pedidos siguen fail-closed). El slug sigue obligatorio.
	 */
	MenuDTO getMenu(String slug, Long branchId);

	/**
	 * Public identity of one table of the business addressed by the slug, for
	 * the anonymous customer menu badge opened from a per-table QR.
	 *
	 * <p>Slug-scoped (S4 enmendada de odd/tasks/retire-menu-route.md): the
	 * business is resolved from the slug — an unknown slug, a deactivated
	 * local, an unknown table or a table of another business all answer
	 * NOT_FOUND.</p>
	 *
	 * @param slug the business slug carried by the QR URL
	 * @param tableId the table id carried by the QR URL
	 * @return the table's public identity (id, tableNumber, tableName)
	 * @throws BusinessException NOT_FOUND when the slug does not resolve to an
	 *         active business or the table does not belong to it
	 */
	MenuTableDTO getPublicTable(String slug, Long tableId);

}
