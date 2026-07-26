package com.company.erp.api;

import com.company.erp.masterdata.product.ProductCatalogService;
import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductCommands.CreateSku;
import com.company.erp.masterdata.product.ProductCommands.CreateSpu;
import com.company.erp.masterdata.product.ProductCommands.UpdateSku;
import com.company.erp.masterdata.product.ProductCommands.UpdateSpu;
import com.company.erp.masterdata.product.ProductErrors.ProductNotFoundException;
import com.company.erp.masterdata.product.ProductQueryService;
import com.company.erp.masterdata.product.ProductStatus;
import com.company.erp.masterdata.product.ProductViews.ProductDetail;
import com.company.erp.masterdata.product.ProductViews.ProductFilter;
import com.company.erp.masterdata.product.ProductViews.ProductPage;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/products/spus")
public class ProductController {
  private final ProductCatalogService catalog;
  private final ProductQueryService queries;

  public ProductController(ProductCatalogService catalog, ProductQueryService queries) {
    this.catalog = catalog;
    this.queries = queries;
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ProductPage list(
      @RequestParam(name = "keyword", required = false) String keyword,
      @RequestParam(name = "barcode", required = false) String barcode,
      @RequestParam(name = "brandId", required = false) String brandId,
      @RequestParam(name = "categoryId", required = false) String categoryId,
      @RequestParam(name = "status", required = false) ProductStatus status,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    return queries.list(
        new ProductFilter(keyword, barcode, brandId, categoryId, status),
        page,
        size);
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ProductDetail get(@PathVariable("id") String id) {
    return queries.get(id).orElseThrow(() -> new ProductNotFoundException(id));
  }

  @PostMapping
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<CreatedResponse> create(
      @RequestBody CreateProductRequest request,
      JwtAuthenticationToken authentication) {
    var id = catalog.createSpu(request.toCommand(), actor(authentication));
    return ResponseEntity.created(URI.create("/api/products/spus/" + id))
        .body(new CreatedResponse(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  ResponseEntity<Void> update(
      @PathVariable("id") String id,
      @RequestBody UpdateProductRequest request,
      JwtAuthenticationToken authentication) {
    catalog.updateSpu(id, request.toCommand(), actor(authentication));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/status")
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<Void> changeStatus(
      @PathVariable("id") String id,
      @RequestBody ChangeProductStatusRequest request,
      JwtAuthenticationToken authentication) {
    catalog.changeStatus(
        id,
        request.status(),
        request.version(),
        request.reason(),
        actor(authentication));
    return ResponseEntity.noContent().build();
  }

  @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<CreatedResponse> addImage(
      @PathVariable("id") String id,
      @RequestPart("file") MultipartFile file,
      @RequestParam("version") long version,
      JwtAuthenticationToken authentication) throws IOException {
    var imageId = catalog.addImage(
        id,
        file.getInputStream(),
        file.getSize(),
        file.getContentType(),
        version,
        actor(authentication));
    return ResponseEntity.created(
        URI.create("/api/products/spus/" + id + "/images/" + imageId))
        .body(new CreatedResponse(imageId));
  }

  @PutMapping("/{id}/images/order")
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<Void> reorderImages(
      @PathVariable("id") String id,
      @RequestBody ReorderImagesRequest request,
      JwtAuthenticationToken authentication) {
    catalog.reorderImages(id, request.imageIds(), request.version(), actor(authentication));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}/images/{imageId}")
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<Void> removeImage(
      @PathVariable("id") String id,
      @PathVariable("imageId") String imageId,
      @RequestParam("version") long version,
      @RequestParam("reason") String reason,
      JwtAuthenticationToken authentication) {
    catalog.removeImage(id, imageId, version, reason, actor(authentication));
    return ResponseEntity.noContent().build();
  }

  private static AuditActor actor(JwtAuthenticationToken authentication) {
    Set<String> roles = authentication.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .filter(authority -> authority.startsWith("ROLE_"))
        .map(authority -> authority.substring("ROLE_".length()))
        .collect(Collectors.toUnmodifiableSet());
    return new AuditActor(authentication.getName(), roles);
  }

  record CreatedResponse(String id) {
  }

  record ChangeProductStatusRequest(ProductStatus status, long version, String reason) {
  }

  record ReorderImagesRequest(List<String> imageIds, long version) {
  }

  record CreateProductRequest(
      String spuCode,
      String name,
      String brandId,
      String categoryId,
      Map<String, String> attributes,
      List<CreateSkuRequest> skus) {
    CreateSpu toCommand() {
      return new CreateSpu(
          spuCode,
          name,
          brandId,
          categoryId,
          attributes,
          skus == null ? List.of() : skus.stream().map(CreateSkuRequest::toCommand).toList());
    }
  }

  record UpdateProductRequest(
      String name,
      String brandId,
      String categoryId,
      Map<String, String> attributes,
      List<UpdateSkuRequest> skus,
      List<CreateSkuRequest> newSkus,
      long version) {
    UpdateSpu toCommand() {
      return new UpdateSpu(
          name,
          brandId,
          categoryId,
          attributes,
          skus == null ? List.of() : skus.stream().map(UpdateSkuRequest::toCommand).toList(),
          newSkus == null ? List.of() : newSkus.stream().map(CreateSkuRequest::toCommand).toList(),
          version);
    }
  }

  record CreateSkuRequest(
      String skuCode,
      String name,
      String barcode,
      Map<String, String> specifications,
      String unit) {
    CreateSku toCommand() {
      return new CreateSku(skuCode, name, barcode, specifications, unit);
    }
  }

  record UpdateSkuRequest(
      String id,
      String name,
      String barcode,
      Map<String, String> specifications,
      String unit,
      ProductStatus status,
      long version) {
    UpdateSku toCommand() {
      return new UpdateSku(id, name, barcode, specifications, unit, status, version);
    }
  }
}
