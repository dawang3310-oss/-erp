package com.company.erp.api;

import com.company.erp.masterdata.product.ProductReferenceDataService;
import com.company.erp.masterdata.product.ProductReferenceDataService.CategoryItem;
import com.company.erp.masterdata.product.ProductReferenceDataService.ReferenceItem;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/reference")
public class ProductReferenceDataController {
  private final ProductReferenceDataService references;

  public ProductReferenceDataController(ProductReferenceDataService references) {
    this.references = references;
  }

  @GetMapping("/brands")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  List<ReferenceItem> brands(
      @RequestParam(name = "query", required = false) String query) {
    return references.findBrands(query);
  }

  @PostMapping("/brands")
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<ReferenceItem> createBrand(@RequestBody CreateReferenceRequest request) {
    var created = references.createBrand(request.name());
    return ResponseEntity.created(
        URI.create("/api/products/reference/brands/" + created.id())).body(created);
  }

  @GetMapping("/categories")
  @PreAuthorize("hasAnyRole('PRODUCT_VIEW', 'PRODUCT_OPERATOR', 'PRODUCT_ADMIN')")
  List<CategoryItem> categories(
      @RequestParam(name = "query", required = false) String query) {
    return references.findCategories(query);
  }

  @PostMapping("/categories")
  @PreAuthorize("hasRole('PRODUCT_ADMIN')")
  ResponseEntity<CategoryItem> createCategory(@RequestBody CreateCategoryRequest request) {
    var created = references.createCategory(request.name(), request.parentId());
    return ResponseEntity.created(
        URI.create("/api/products/reference/categories/" + created.id())).body(created);
  }

  record CreateReferenceRequest(String name) {
  }

  record CreateCategoryRequest(String name, String parentId) {
  }
}
