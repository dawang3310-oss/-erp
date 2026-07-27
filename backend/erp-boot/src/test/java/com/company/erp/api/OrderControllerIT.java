package com.company.erp.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.erp.identity.SecurityConfiguration;
import com.company.erp.order.OrderQueryService;
import com.company.erp.order.OrderQueryService.SalesOrderView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(SecurityConfiguration.class)
class OrderControllerIT {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private OrderQueryService queries;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void requestWithoutTokenIsUnauthorized() throws Exception {
    mvc.perform(get("/api/orders/SO-1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void operatorSeesMaskedReceiverPhone() throws Exception {
    when(queries.get("SO-1")).thenReturn(Optional.of(order("SO-1", "SHOP-1")));

    mvc.perform(get("/api/orders/SO-1").with(jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_ORDER_VIEW"))
            .jwt(token -> token.subject("operator-1").claim("shopIds", List.of("SHOP-1")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receiverPhone").value("138****0000"));
  }

  @Test
  void piiViewerSeesFullReceiverPhone() throws Exception {
    when(queries.get("SO-1")).thenReturn(Optional.of(order("SO-1", "SHOP-1")));

    mvc.perform(get("/api/orders/SO-1").with(jwt()
            .authorities(
                new SimpleGrantedAuthority("ROLE_ORDER_VIEW"),
                new SimpleGrantedAuthority("ROLE_PII_VIEW"))
            .jwt(token -> token.subject("auditor-1").claim("shopIds", List.of("SHOP-1")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receiverPhone").value("13812340000"));
  }

  @Test
  void operatorCannotReadOrderFromUnassignedShop() throws Exception {
    when(queries.get("SO-2")).thenReturn(Optional.of(order("SO-2", "SHOP-2")));

    mvc.perform(get("/api/orders/SO-2").with(jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_ORDER_VIEW"))
            .jwt(token -> token.subject("operator-1").claim("shopIds", List.of("SHOP-1")))))
        .andExpect(status().isForbidden());
  }

  @Test
  void listIsFilteredByTokenShopScope() throws Exception {
    when(queries.list(List.of("SHOP-1"), "READY_TO_FULFILL", "PINDUODUO", 0, 20))
        .thenReturn(List.of(order("SO-1", "SHOP-1")));

    mvc.perform(get("/api/orders")
            .param("status", "READY_TO_FULFILL")
            .param("platform", "PINDUODUO")
            .with(jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ORDER_VIEW"))
                .jwt(token -> token.subject("operator-1").claim("shopIds", List.of("SHOP-1")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("SO-1"));
  }

  @Test
  void negativePageIsRejectedAsBadRequest() throws Exception {
    mvc.perform(get("/api/orders").param("page", "-1").with(jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_ORDER_VIEW"))
            .jwt(token -> token.subject("operator-1").claim("shopIds", List.of("SHOP-1")))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  private static SalesOrderView order(String id, String shopId) {
    return new SalesOrderView(
        id,
        "PINDUODUO",
        shopId,
        "PO-1",
        "READY_TO_FULFILL",
        Instant.parse("2026-07-23T01:00:00Z"),
        List.of(),
        "",
        "13812340000");
  }
}
