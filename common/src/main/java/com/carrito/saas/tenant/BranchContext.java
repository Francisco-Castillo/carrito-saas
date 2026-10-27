package com.carrito.saas.tenant;

/**
 * Sucursal activa del request (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T3). Espejo de {@link TenantContext}: lo llena {@code JwtFilter} desde el
 * claim {@code branchId} del JWT y lo limpia en el finally del filtro.
 *
 * <p>Puede estar vacío legítimamente: tokens sin claim (viejos o de
 * plataforma) y negocios sin sucursales. La resolución con fallback (una
 * sola activa → esa; varias → exigir selección) vive en
 * {@code SecurityService.getCurrentBranchId()}, no acá: el contexto sólo
 * transporta el claim.</p>
 */
public class BranchContext {

	private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

	public static void set(Long branchId) {
		CURRENT.set(branchId);
	}

	public static Long get() {
		return CURRENT.get();
	}

	public static void clear() {
		CURRENT.remove();
	}

}
