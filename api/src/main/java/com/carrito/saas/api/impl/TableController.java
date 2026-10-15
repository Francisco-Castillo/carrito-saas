package com.carrito.saas.api.impl;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.carrito.saas.api.ITableController;
import com.carrito.saas.dto.CreateTableRequestDTO;
import com.carrito.saas.dto.GenerateTablesRequestDTO;
import com.carrito.saas.dto.QrPdfRequestDTO;
import com.carrito.saas.dto.TableResponseDTO;
import com.carrito.saas.dto.UpdateTableRequestDTO;
import com.carrito.saas.service.interfaces.ITableService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * POST /api/tables PUT /api/tables/{id} DELETE /api/tables/{id} GET /api/tables
 * GET /api/tables/{id} POST /api/tables/bulk GET /api/tables/{id}/qr GET
 * /api/tables/{id}/qr/pdf GET /api/tables/qr/pdf
 */
@RestController
@RequestMapping("/api/tables")
@Slf4j
public class TableController implements ITableController {

	private final ITableService service;

	public TableController(ITableService service) {
		super();
		this.service = service;
	}

	@Override
	@PostMapping
	public ResponseEntity<TableResponseDTO> create(@Valid CreateTableRequestDTO request) {
		return ResponseEntity.ok(service.create(request));
	}

	@Override
	@PutMapping("/{id}")
	public TableResponseDTO update(Long id, @Valid UpdateTableRequestDTO request) {
		return service.update(id, request);
	}

	@Override
	@DeleteMapping("/{id}")
	public void delete(Long id) {
		service.delete(id);
	}

	@Override
	@GetMapping
	public Page<TableResponseDTO> findAll(Pageable pageable) {
		return service.findAll(pageable);
	}

	@Override
	@GetMapping("/{id}")
	public TableResponseDTO findById(Long id) {
		return service.findById(id);
	}

	@Override
	@PostMapping("/bulk")
	public List<TableResponseDTO> generateBulk(@Valid GenerateTablesRequestDTO request) {
		return service.generateBulk(request);
	}

	@Override
	@GetMapping("/{id}/qr")
	public ResponseEntity<byte[]> downloadQr(Long id, Integer size) {
		byte[] qr = service.generateQr(id, size);

		return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(qr);
	}

	@Override
	@GetMapping("/{id}/qr/pdf")
	public ResponseEntity<byte[]> downloadPdf(Long id,QrPdfRequestDTO request) {
		byte[] pdf = service.generatePdf(id, request);

		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=table-" + id + ".pdf")
				.contentType(MediaType.APPLICATION_PDF).body(pdf);
	}

	@Override
	@GetMapping("/qr/pdf")
	public ResponseEntity<byte[]> downloadAllPdf(QrPdfRequestDTO request) {
		byte[] pdf = service.generatePdfAll(request);

		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tables.pdf")
				.contentType(MediaType.APPLICATION_PDF).body(pdf);
	}

	

}
