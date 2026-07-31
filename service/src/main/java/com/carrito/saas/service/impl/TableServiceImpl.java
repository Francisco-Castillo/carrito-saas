package com.carrito.saas.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.CreateTableRequestDTO;
import com.carrito.saas.dto.GenerateTablesRequestDTO;
import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.dto.TableResponseDTO;
import com.carrito.saas.dto.UpdateTableRequestDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.repository.enums.TableStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.TableRepository;
import com.carrito.saas.security.SecurityService;
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

	// private final QrProperties qrProperties;

	private final SecurityService securityService;
	
	private final IRestaurantTableMapper restaurantTableMapper;

	@Override
	@Transactional(readOnly = true)
	public byte[] generateQr(Long tableId, Integer size) {
		Long businessId = securityService.getCurrentBusinessId();
		RestaurantTable table = findOwnedTable(tableId, businessId);

		/*
		 * String url = qrProperties.getPublicMenuUrl() + "/" + table.getQrToken();
		 */
		String url = "" + "/" + table.getQrToken();

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

		List<RestaurantTable> tables = tableRepository.findByBusinessId(businessId);

		if (tables.isEmpty()) {

			// throw new BusinessException("No tables configured");
		}

		return qrPdfService.generateTablesPdf(tables,config);
	}

	@Override
	public TableResponseDTO create(CreateTableRequestDTO request) {
		Long businessId = securityService.getCurrentBusinessId();

		// validateTableNumber(businessId, request.getTableNumber());

		Business business = businessRepository.findById(businessId)
				.orElseThrow(() -> new RuntimeException("Business not found"));

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

		//table.setActive(request.getActive());

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
				.orElseThrow(() -> new RuntimeException("Business not found"));

		List<RestaurantTable> tables = new ArrayList<>();

		for (int i = 1; i <= request.getQuantity(); i++) {

			if (tableRepository.existsByBusinessIdAndTableNumber(businessId, i)) {

				continue;
			}

			RestaurantTable table = new RestaurantTable();

			table.setBusiness(business);
			table.setTableNumber(i);
			table.setTableName("Mesa " + i);
			table.setQrToken(UUID.randomUUID().toString());

			//table.setActive(Boolean.TRUE);

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
				.orElseThrow(() -> new RuntimeException(String.format("Table %d not found", tableId)));
	}

	private void validateTableNumber(Long businessId, Integer tableNumber) {

		if (tableRepository.existsByBusinessIdAndTableNumber(businessId, tableNumber)) {

			//throw new BusinessException("Table number already exists");
		}
	}

	private void validateUpdateTableNumber(Long businessId, Integer tableNumber, Long tableId) {

		if (tableRepository.existsByBusinessIdAndTableNumberAndIdNot(businessId, tableNumber, tableId)) {

			//throw new BusinessException("Table number already exists");
		}
	}

	private TableResponseDTO buildResponse(RestaurantTable table) {

		TableResponseDTO response=	restaurantTableMapper.toDTO(table);

		//response.setQrUrl(qrProperties.getPublicMenuUrl() + "/" + table.getQrToken());
		
		response.setQrUrl("http://localhost:8080"+"/" + table.getQrToken());

		return response;
	}

}
