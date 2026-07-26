package com.company.erp.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.erp.identity.SecurityConfiguration;
import com.company.erp.masterdata.product.ProductReferenceDataService;
import com.company.erp.masterdata.product.ProductReferenceDataService.CategoryItem;
import com.company.erp.masterdata.product.ProductReferenceDataService.ReferenceItem;
import com.company.erp.order.OrderQueryService;
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

@WebMvcTest(ProductReferenceDataController.class)
@Import(SecurityConfiguration.class)
class ProductReferenceDataControllerIT {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private ProductReferenceDataService references;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @MockitoBean
  private OrderQueryService orderQueries;

  @Test
  void productViewerCanSearchReferenceDataButCannotCreateIt() throws Exception {
    when(references.findBrands("星")).thenReturn(List.of(new ReferenceItem("BRAND-1", "星链")));
    when(references.findCategories("杯")).thenReturn(List.of(
        new CategoryItem("CATEGORY-1", "杯具", null, "/CATEGORY-1")));

    mvc.perform(get("/api/products/reference/brands")
            .queryParam("query", "星")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("BRAND-1"))
        .andExpect(jsonPath("$[0].name").value("星链"));

    mvc.perform(get("/api/products/reference/categories")
            .queryParam("query", "杯")
            .with(productJwt("PRODUCT_VIEW")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].path").value("/CATEGORY-1"));

    mvc.perform(post("/api/products/reference/brands")
            .with(productJwt("PRODUCT_VIEW"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"新品牌"}
                """))
        .andExpect(status().isForbidden());
  }

  @Test
  void productAdminCanCreateBrandAndCategory() throws Exception {
    when(references.createBrand("新品牌"))
        .thenReturn(new ReferenceItem("BRAND-2", "新品牌"));
    when(references.createCategory("保温杯", "CATEGORY-1"))
        .thenReturn(new CategoryItem(
            "CATEGORY-2",
            "保温杯",
            "CATEGORY-1",
            "/CATEGORY-1/CATEGORY-2"));

    mvc.perform(post("/api/products/reference/brands")
            .with(productJwt("PRODUCT_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"新品牌"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("BRAND-2"));

    mvc.perform(post("/api/products/reference/categories")
            .with(productJwt("PRODUCT_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"保温杯","parentId":"CATEGORY-1"}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.parentId").value("CATEGORY-1"));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor productJwt(
      String role) {
    return jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_" + role))
        .jwt(token -> token.subject("user-1").claim("roles", List.of(role)));
  }
}
