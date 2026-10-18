package com.carrito.saas.service.interfaces;

import java.time.LocalDate;
import java.util.List;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.dto.OrderManagementDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.enums.OrderStatus;

public interface IOrderService {

	OrderDTO createOrder(String slug, OrderRequestDTO request);

	List<OrderKitchenDTO> getActiveOrders(Long businessId);

	List<OrderKitchenDTO> getActiveOrders();

	/**
	 * <h2>Pedidos del día para la gestión del mostrador</h2>
	 *
	 * <p>Devuelve los pedidos del negocio del JWT en la fecha indicada,
	 * con TODOS los estados (incluidos DELIVERED y CANCELLED, con su motivo):
	 * es la vista completa del día que el KDS no da. La fecha es opcional;
	 * null significa hoy (el mismo "hoy" server-local del dashboard).</p>
	 *
	 * @param date fecha a consultar (null = hoy)
	 * @return pedidos del día con el payload rico de gestión
	 */
	List<OrderManagementDTO> getOrdersByDay(LocalDate date);

	OrderDTO updateStatus(Long orderId, OrderStatus status);

	/**
	 * <h2>Cancelación de Orden</h2>
	 *
	 * <p>
	 * Método encargado de cancelar una orden y gestionar el impacto en el stock.
	 * </p>
	 *
	 * <h3>Reglas de negocio</h3>
	 *
	 * <ul>
	 * <li><b>Pedido NO preparado (estado NEW):</b><br>
	 * Se devuelve el stock completo de los productos asociados a la orden.</li>
	 * <br>
	 * <li><b>Pedido en preparación o posterior:</b><br>
	 * No se devuelve stock, ya que el proceso de cocina ya fue iniciado.<br>
	 * Solo se registra el motivo de cancelación para auditoría.</li>
	 * </ul>
	 *
	 * <h3>Consideraciones</h3>
	 *
	 * <ul>
	 * <li>Los ítems que pertenecen a combos (isComboRoot = false) son los únicos
	 * que afectan el stock.</li>
	 * <li>El motivo de cancelación es obligatorio para análisis y métricas.</li>
	 * <li>La operación es transaccional para garantizar consistencia de datos.</li>
	 * </ul>
	 *
	 * @param orderId  Identificador único de la orden a cancelar
	 * @param reasonId Identificador del motivo de cancelación
	 * @param note     Descripción adicional opcional sobre la cancelación
	 *
	 * @return {@code OrderDTO} con el estado actualizado de la orden
	 */
	OrderDTO cancelOrder(Long orderId, Long reasonId, String note);

}
