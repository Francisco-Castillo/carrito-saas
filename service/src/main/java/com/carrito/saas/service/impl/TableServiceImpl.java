package com.carrito.saas.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.CreateTableRequestDTO;
import com.carrito.saas.dto.GenerateTablesRequestDTO;
import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.dto.TableResponseDTO;
import com.carrito.saas.dto.UpdateTableRequestDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.security.SecurityService;
import com.carrito.saas.service.interfaces.IMenuUrlBuilder;
import com.carrito.saas.service.interfaces.IQrCodeService;
import com.carrito.saas.service.interfaces.IQrPdfService;
import com.carrito.saas.service.interfaces.ITableService;
import com.carrito.saas.service.mapper.interfaces.IRestaurantTableMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TableServiceImpl implements ITableService {

	private final TableRepository tableRepository;

	private final BusinessRepository businessRepository;

	// private final TableMapper tableMapper;

	// private final JwtContext jwtContext;

	private final IQrCodeService qrCodeService;

	private final IQrPdfService qrPdfService;

	private final IMenuUrlBuilder menuUrlBuilder;

	private final SecurityService securityService;
	
	private final IRestaurantTableMapper restaurantTableMapper;

	@Override
	@Transactional(readOnly = true)
	public byte[] generateQr(Long tableId, Integer size) {
		Long businessId = securityService.getCurrentBusinessId();
		RestaurantTable table = findOwnedTable(tableId, businessId);

		String url = menuUrlBuilder.buildTableMenuUrl(table.getBusiness().getSlug(), table.getId());

		return qrCodeService.generateQr(url, size);
	}

	@Override
	@Transactional(readOnly = true)
	public byte[] generatePdf(Long tableId, QrPdfRequestDTO config) {
		Long businessId = securityService.getCurrentBusinessId();
		RestaurantTable table = findOwnedTable(tableId, businessId);

		return qrPdfService.generateTablePdf(table, config);
	}

	@Override
	@Transactional(readOnly = true)
	public byte[] generatePdfAll(QrPdfRequestDTO config) {
		Long businessId = securityService.getCurrentBusinessId();

		// Una mesa dada de baja ya no existe públicamente (su QR responde 404),
		// así que tampoco entra en el PDF: imprimir su código sería entregarle
		// al dueño un QR muerto sin aviso.
		List<RestaurantTable> tables = tableRepository.findByBusinessId(businessId).stream()
				.filter(table -> table.getStatus() != TableStatus.DISABLED)
				.toList();

		if (tables.isEmpty()) {
			throw new BusinessException("No hay mesas activas para generar el PDF", ErrorType.NOT_FOUND);
		}

		return qrPdfService.generateTablesPdf(tables,config);
	}

	@Override
	public TableResponseDTO create(CreateTableRequestDTO request) {
		Long businessId = securityService.getCurrentBusinessId();

		validateTableNumber(businessId, request.getTableNumber());

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		RestaurantTable table = new RestaurantTable();

		table.setBusiness(business);
		table.setTableNumber(request.getTableNumber());
		table.setTableName(request.getTableName());
		table.setQrToken(UUID.randomUUID().toString());
		table.setStatus(TableStatus.AVAILABLE);
		table.setCreatedAt(LocalDateTime.now());
		table.setUpdatedAt(LocalDateTime.now());

		tableRepository.save(table);

		return buildResponse(table);
	}

	@Override
	public TableResponseDTO update(Long id, UpdateTableRequestDTO request) {
		Long businessId = securityService.getCurrentBusinessId();

		RestaurantTable table = findOwnedTable(id, businessId);

		validateUpdateTableNumber(businessId, request.getTableNumber(), id);

		table.setTableNumber(request.getTableNumber());

		table.setTableName(request.getTableName());

		table.setStatus(request.getStatus());

		table.setUpdatedAt(LocalDateTime.now());

		return buildResponse(table);
	}

	@Override
	public void delete(Long id) {
		Long businessId = securityService.getCurrentBusinessId();

		RestaurantTable table = findOwnedTable(id, businessId);

		// Soft delete

		table.setStatus(TableStatus.DISABLED);
		table.setUpdatedAt(LocalDateTime.now());

	}

	@Override
	public List<TableResponseDTO> generateBulk(GenerateTablesRequestDTO request) {
		Long businessId = securityService.getCurrentBusinessId();

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new BusinessException("Local no encontrado", ErrorType.NOT_FOUND));

		// quantity son mesas NUEVAS y toman los números LIBRES más bajos: los
		// que dejó una mesa dada de baja (la baja es soft delete, la fila sigue
		// existiendo para el historial) o cualquier hueco de numeración. Si no
		// hay huecos, la numeración sigue después de la más alta. Antes se
		// completaba 1..N salteando las existentes, así que con Mesa 1..12 pedir
		// 3 no creaba nada; y numerar siempre después de la última dejaba al
		// dueño con Mesa 1, 3, 4 sin forma de recuperar la 2.
		List<RestaurantTable> existingTables = tableRepository.findByBusinessId(businessId);

		Set<Integer> numbersInUse = existingTables.stream()
				.filter(table -> table.getStatus() != TableStatus.DISABLED)
				.map(RestaurantTable::getTableNumber)
				.filter(number -> number != null)
				.collect(Collectors.toSet());

		List<Integer> newNumbers = new ArrayList<>();

		for (int candidate = 1; newNumbers.size() < request.getQuantity(); candidate++) {
			if (!numbersInUse.contains(candidate)) {
				newNumbers.add(candidate);
			}
		}

		List<RestaurantTable> tables = new ArrayList<>();

		for (int tableNumber : newNumbers) {

			RestaurantTable table = new RestaurantTable();

			table.setBusiness(business);
			table.setTableNumber(tableNumber);
			table.setTableName("Mesa " + tableNumber);
			table.setQrToken(UUID.randomUUID().toString());

			table.setStatus(TableStatus.AVAILABLE);

			table.setCreatedAt(LocalDateTime.now());
			table.setUpdatedAt(LocalDateTime.now());

			tables.add(table);
		}

		tableRepository.saveAll(tables);

		return tables.stream().map(this::buildResponse).toList();
	}
	@Override
	public Page<TableResponseDTO> findAll(Pageable pageable) {
		Long businessId = securityService.getCurrentBusinessId();

		return tableRepository.findByBusinessId(businessId, pageable).map(this::buildResponse);
	}

	@Override
	@Transactional(readOnly = true)
	public TableResponseDTO findById(Long tableId) {

		Long businessId = securityService.getCurrentBusinessId();
		RestaurantTable table = findOwnedTable(tableId, businessId);

		return buildResponse(table);
	}

	private RestaurantTable findOwnedTable(Long tableId, Long businessId) {

		return tableRepository.findByIdAndBusinessId(tableId, businessId)
				.orElseThrow(() -> new BusinessException("Mesa no encontrada", ErrorType.NOT_FOUND));
	}

	/**
	 * Un número está tomado sólo si lo usa una mesa VIVA: la baja es soft
	 * delete, así que una mesa dada de baja conserva su fila (historial y QR
	 * viejo) pero libera su número, igual que en el alta por lote.
	 */
	private void validateTableNumber(Long businessId, Integer tableNumber) {

		if (tableRepository.existsByBusinessIdAndTableNumberAndStatusNot(
				businessId, tableNumber, TableStatus.DISABLED)) {

			throw new BusinessException("Ya existe una mesa con ese número", ErrorType.CONFLICT);
		}
	}

	private void validateUpdateTableNumber(Long businessId, Integer tableNumber, Long tableId) {

		if (tableRepository.existsByBusinessIdAndTableNumberAndIdNotAndStatusNot(
				businessId, tableNumber, tableId, TableStatus.DISABLED)) {

			throw new BusinessException("Ya existe una mesa con ese número", ErrorType.CONFLICT);
		}
	}

	private TableResponseDTO buildResponse(RestaurantTable table) {

		TableResponseDTO response=	restaurantTableMapper.toDTO(table);

		response.setQrUrl(menuUrlBuilder.buildTableMenuUrl(table.getBusiness().getSlug(), table.getId()));

		return response;
	}

}
