package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.MenuTableDTO;

public interface IMenuService {

	MenuDTO getMenu(String slug);

	/**
	 * Public identity of one table of the deployment's single business, for
	 * the anonymous customer menu badge opened from a per-table QR.
	 *
	 * @param tableId the table id carried by the QR URL
	 * @return the table's public identity (id, tableNumber, tableName)
	 * @throws BusinessException NOT_FOUND when the deployment has zero or more
	 *         than one business, the business is deactivated, or the table does
	 *         not belong to it
	 */
	MenuTableDTO getPublicTable(Long tableId);

}
