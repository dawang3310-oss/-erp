package com.company.erp.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.erp.identity.SecurityConfiguration;
import com.company.erp.masterdata.product.ProductCatalogService;
import com.company.erp.masterdata.product.ProductErrors.StaleProductVersionException;
import com.company.erp.masterdata.product.ProductQueryService;
import com.company.erp.masterdata.product.ProductStatus;
import com.company.erp.masterdata.product.ProductViews.ProductPage;
import com.company.erp.masterdata.product.ProductViews.ProductSummary;
import com.company.erp.order.OrderQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(SecurityConfiguration.class)
class ProductControllerIT {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ProductCatalogService catalog;

  @MockitoBean
  private ProductQueryService queries;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private OrderQueryService orderQueries;

  @Test
  void productViewerCanListButCannotCreate() throws Exception {
    when(queries.list(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(new ProductPage(List.of(summary()), 0, 20, 1));

    mvc.perform(get("/api/products/spus").with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].spuCode").value("SPU-1"))
        .andExpect(jsonPath("$.items[0].skuCount").value(2));

    mvc.perform(post("/api/products/spus")
            .with(productJwt("PRODUCT_VIEW"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isForbidden());
  }

  @Test
  void productAdminCanCreateAndChangeStatus() throws Exception {
    when(catalog.createSpu(any(), any())).thenReturn("PRODUCT-1");

    mvc.perform(post("/api/products/spus")
            .with(productJwt("PRODUCT_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("PRODUCT-1"));

    mvc.perform(post("/api/products/spus/PRODUCT-1/status")
            .with(productJwt("PRODUCT_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"status":"ACTIVE","version":0,"reason":"审核通过"}
                """))
        .andExpect(status().isNoContent());
  }

  @Test
  void productOperatorCanEditButCannotChangeLifecycle() throws Exception {
    mvc.perform(put("/api/products/spus/PRODUCT-1")
            .with(productJwt("PRODUCT_OPERATOR"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody()))
        .andExpect(status().isNoContent());

    mvc.perform(post("/api/products/spus/PRODUCT-1/status")
            .with(productJwt("PRODUCT_OPERATOR"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"status":"ARCHIVED","version":1,"reason":"停止经营"}
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void staleProductVersionReturnsConflictContract() throws Exception {
    doThrow(new StaleProductVersionException("PRODUCT-1"))
        .when(catalog).changeStatus(any(), any(), any(Long.class), any(), any());

    mvc.perform(post("/api/products/spus/PRODUCT-1/status")
            .with(productJwt("PRODUCT_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"status":"ACTIVE","version":0,"reason":"审核通过"}
                """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_PRODUCT_VERSION"));
  }

  @Test
  void unauthenticatedProductRequestIsRejected() throws Exception {
    mvc.perform(get("/api/products/spus"))
        .andExpect(status().isUnauthorized());
  }

  private static ProductSummary summary() {
    return new ProductSummary(
        "PRODUCT-1",
        "SPU-1",
        "智能水杯",
        null,
        null,
        null,
        null,
        ProductStatus.DRAFT,
        2,
        0,
        null,
        Instant.parse("2026-07-26T08:00:00Z"),
        0);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor productJwt(
      String role) {
    return jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_" + role))
        .jwt(token -> token.subject("user-1").claim("roles", List.of(role)));
  }

  private static String createBody() {
    return """
        {
          "spuCode":"SPU-1",
          "name":"智能水杯",
          "attributes":{"material":"不锈钢"},
          "skus":[{
            "skuCode":"SKU-1",
            "name":"曜石黑 500ml",
            "barcode":"690000000001",
            "specifications":{"color":"曜石黑"},
            "unit":"件"
          }]
        }
        """;
  }

  private static String updateBody() {
    return """
        {
          "name":"智能水杯升级版",
          "attributes":{"material":"钛合金"},
          "skus":[],
          "newSkus":[{
            "skuCode":"SKU-2",
            "name":"雪山白 500ml",
            "barcode":"690000000002",
            "specifications":{"color":"雪山白"},
            "unit":"件"
          }],
          "version":0
        }
        """;
  }
}
