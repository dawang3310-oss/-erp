package com.company.erp.api;

import com.company.erp.masterdata.importing.ProductImportService;
import com.company.erp.masterdata.importing.ProductJobViews.ExportJobView;
import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductObjectStore;
import com.company.erp.masterdata.product.ProductStatus;
import com.company.erp.masterdata.product.ProductViews.ProductFilter;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/exports")
public class ProductExportController {
  private static final MediaType EXCEL = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ProductImportService jobs;
  private final ProductObjectStore objects;
  private final TaskExecutor executor;

  public ProductExportController(
      ProductImportService jobs,
      ProductObjectStore objects,
      @Qualifier("productJobExecutor") TaskExecutor executor) {
    this.jobs = jobs;
    this.objects = objects;
    this.executor = executor;
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ResponseEntity<JobAcceptedResponse> create(
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "barcode", required = false) String barcode,
      @RequestParam(name = "brandId", required = false) String brandId,
      @RequestParam(name = "categoryId", required = false) String categoryId,
      @RequestParam(name = "status", required = false) ProductStatus status,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      JwtAuthenticationToken authentication) {
    var jobId = jobs.createExport(
        new ProductFilter(keyword, barcode, brandId, categoryId, status),
        idempotencyKey,
        actor(authentication));
    executor.execute(() -> jobs.executeExportJob(jobId));
    return ResponseEntity.accepted().body(new JobAcceptedResponse(jobId));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ExportJobView get(@PathVariable("id") String id) {
    return jobs.getExportJob(id);
  }

  @GetMapping("/{id}/file")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ResponseEntity<byte[]> download(@PathVariable("id") String id) throws IOException {
    var job = jobs.getExportJob(id);
    if (!"SUCCEEDED".equals(job.status()) || job.objectKey() == null) {
      return ResponseEntity.notFound().build();
    }
    try (var input = objects.get(job.objectKey())) {
      var disposition = ContentDisposition.attachment()
          .filename("product-export-" + id + ".xlsx")
          .build();
      return ResponseEntity.ok()
          .contentType(EXCEL)
          .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
          .body(input.readAllBytes());
    }
  }

  private static AuditActor actor(JwtAuthenticationToken authentication) {
    Set<String> roles = authentication.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .filter(authority -> authority.startsWith("ROLE_"))
        .map(authority -> authority.substring("ROLE_".length()))
        .collect(Collectors.toUnmodifiableSet());
    return new AuditActor(authentication.getName(), roles);
  }

  record JobAcceptedResponse(String id) {
  }
}
