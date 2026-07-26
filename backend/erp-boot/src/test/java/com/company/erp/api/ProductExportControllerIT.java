package com.company.erp.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.erp.identity.SecurityConfiguration;
import com.company.erp.masterdata.importing.ProductImportService;
import com.company.erp.masterdata.importing.ProductJobViews.ExportJobView;
import com.company.erp.masterdata.product.ProductObjectStore;
import com.company.erp.order.OrderQueryService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductExportController.class)
@Import(SecurityConfiguration.class)
class ProductExportControllerIT {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ProductImportService jobs;

  @MockitoBean
  private ProductObjectStore objects;

  @MockitoBean(name = "productJobExecutor")
  private TaskExecutor executor;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private OrderQueryService orderQueries;

  @Test
  void viewerCreatesQueriesAndDownloadsAnExport() throws Exception {
    when(jobs.createExport(any(), any(), any())).thenReturn("EXPORT-1");
    when(jobs.getExportJob("EXPORT-1")).thenReturn(new ExportJobView(
        "EXPORT-1",
        "SUCCEEDED",
        "exports/EXPORT-1/products.xlsx",
        Instant.parse("2026-07-26T10:00:00Z"),
        Instant.parse("2026-07-26T10:01:00Z")));
    when(objects.get("exports/EXPORT-1/products.xlsx"))
        .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

    mvc.perform(post("/api/products/exports")
            .queryParam("keyword", "SPU-1")
            .header("Idempotency-Key", "export-request-1")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("EXPORT-1"));

    mvc.perform(get("/api/products/exports/EXPORT-1")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mvc.perform(get("/api/products/exports/EXPORT-1/file")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(header().string(
            "Content-Disposition",
            "attachment; filename=\"product-export-EXPORT-1.xlsx\""));

    verify(executor).execute(any());
  }

  @Test
  void exportRequiresAuthenticationAndAnIdempotencyKey() throws Exception {
    mvc.perform(post("/api/products/exports")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isBadRequest());

    mvc.perform(get("/api/products/exports/EXPORT-1"))
        .andExpect(status().isUnauthorized());
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor productJwt(
      String role) {
    return jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_" + role))
        .jwt(token -> token.subject("user-1").claim("roles", List.of(role)));
  }
}
