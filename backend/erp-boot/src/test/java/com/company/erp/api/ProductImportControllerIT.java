package com.company.erp.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.erp.identity.SecurityConfiguration;
import com.company.erp.masterdata.importing.ProductImportService;
import com.company.erp.masterdata.importing.ProductJobViews.ImportJobView;
import com.company.erp.masterdata.importing.ProductWorkbookService;
import com.company.erp.masterdata.product.ProductObjectStore;
import com.company.erp.order.OrderQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductImportController.class)
@Import(SecurityConfiguration.class)
class ProductImportControllerIT {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ProductImportService imports;

  @MockitoBean
  private ProductWorkbookService workbooks;

  @MockitoBean
  private ProductObjectStore objects;

  @MockitoBean(name = "productJobExecutor")
  private TaskExecutor executor;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private OrderQueryService orderQueries;

  @Test
  void adminUploadsAndConfirmsWhileViewerCanReadStatus() throws Exception {
    var file = new MockMultipartFile(
        "file",
        "products.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1, 2, 3});
    when(imports.createPreflightJob(any(), any(Long.class), any(), any()))
        .thenReturn("IMPORT-1");
    when(imports.getImportJob("IMPORT-1")).thenReturn(job("PREFLIGHT_READY"));

    mvc.perform(multipart("/api/products/imports")
            .file(file)
            .with(productJwt("PRODUCT_ADMIN")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("IMPORT-1"));

    mvc.perform(get("/api/products/imports/IMPORT-1")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PREFLIGHT_READY"));

    mvc.perform(post("/api/products/imports/IMPORT-1/confirm")
            .header("Idempotency-Key", "confirm-1")
            .with(productJwt("PRODUCT_ADMIN")))
        .andExpect(status().isAccepted());

    verify(imports).confirm(any(), any(), any());
    verify(executor, times(2)).execute(any());
  }

  @Test
  void viewerCannotUploadAndTemplateIsDownloadable() throws Exception {
    var file = new MockMultipartFile(
        "file",
        "products.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        new byte[] {1});
    when(workbooks.createTemplate()).thenReturn(new byte[] {7, 8});

    mvc.perform(multipart("/api/products/imports")
            .file(file)
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isForbidden());

    mvc.perform(get("/api/products/imports/template")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(header().string(
            "Content-Disposition",
            "attachment; filename=\"product-import-template.xlsx\""));
  }

  private static ImportJobView job(String status) {
    return new ImportJobView(
        "IMPORT-1",
        "products.xlsx",
        status,
        1,
        0,
        0,
        0,
        0,
        null,
        Instant.parse("2026-07-26T10:00:00Z"),
        null,
        null);
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor productJwt(
      String role) {
    return jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_" + role))
        .jwt(token -> token.subject("user-1").claim("roles", List.of(role)));
  }
}
