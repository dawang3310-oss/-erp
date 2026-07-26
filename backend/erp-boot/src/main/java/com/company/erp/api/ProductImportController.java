package com.company.erp.api;

import com.company.erp.masterdata.importing.ProductImportService;
import com.company.erp.masterdata.importing.ProductJobViews.ImportJobView;
import com.company.erp.masterdata.importing.ProductWorkbookService;
import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductObjectStore;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/products/imports")
public class ProductImportController {
  private static final MediaType EXCEL = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

  private final ProductImportService imports;
  private final ProductWorkbookService workbooks;
  private final ProductObjectStore objects;
  private final TaskExecutor executor;

  public ProductImportController(
      ProductImportService imports,
      ProductWorkbookService workbooks,
      ProductObjectStore objects,
      @Qualifier("productJobExecutor") TaskExecutor executor) {
    this.imports = imports;
    this.workbooks = workbooks;
    this.objects = objects;
    this.executor = executor;
  }

  @GetMapping("/template")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ResponseEntity<byte[]> template() {
    return workbookResponse(workbooks.createTemplate(), "product-import-template.xlsx");
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<JobAcceptedResponse> upload(
      @RequestPart("file") MultipartFile file,
      JwtAuthenticationToken authentication) throws IOException {
    var jobId = imports.createPreflightJob(
        file.getInputStream(),
        file.getSize(),
        file.getOriginalFilename() == null ? "products.xlsx" : file.getOriginalFilename(),
        actor(authentication));
    executor.execute(() -> imports.executePreflight(jobId));
    return ResponseEntity.accepted().body(new JobAcceptedResponse(jobId));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ImportJobView get(@PathVariable("id") String id) {
    return imports.getImportJob(id);
  }

  @PostMapping("/{id}/confirm")
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<JobAcceptedResponse> confirm(
      @PathVariable("id") String id,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      JwtAuthenticationToken authentication) {
    imports.confirm(id, idempotencyKey, actor(authentication));
    executor.execute(() -> imports.executeConfirmedJob(id));
    return ResponseEntity.accepted().body(new JobAcceptedResponse(id));
  }

  @GetMapping("/{id}/errors")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ResponseEntity<byte[]> errors(@PathVariable("id") String id) throws IOException {
    var job = imports.getImportJob(id);
    if (job.errorObjectKey() == null) {
      return ResponseEntity.notFound().build();
    }
    try (var input = objects.get(job.errorObjectKey())) {
      return workbookResponse(input.readAllBytes(), "product-import-errors-" + id + ".xlsx");
    }
  }

  private static ResponseEntity<byte[]> workbookResponse(byte[] bytes, String filename) {
    var disposition = ContentDisposition.attachment().filename(filename).build();
    return ResponseEntity.ok()
        .contentType(EXCEL)
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(bytes);
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
