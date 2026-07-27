package com.company.erp.api;

import com.company.erp.order.OrderQueryService;
import com.company.erp.order.OrderQueryService.SalesOrderLineView;
import com.company.erp.order.OrderQueryService.SalesOrderView;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final OrderQueryService queries;

  public OrderController(OrderQueryService queries) {
    this.queries = queries;
  }

  @GetMapping("/{id}")
  OrderResponse get(@PathVariable("id") String id, JwtAuthenticationToken authentication) {
    var order = queries.get(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    requireShopScope(order.shopId(), shopIds(authentication));
    return toResponse(order, canViewPii(authentication));
  }

  @GetMapping
  List<OrderResponse> list(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "platform", required = false) String platform,
      @RequestParam(name = "page", defaultValue = "0") int page,
      JwtAuthenticationToken authentication) {
    if (page < 0) {
      throw new IllegalArgumentException("page must be non-negative");
    }
    var canViewPii = canViewPii(authentication);
    return queries.list(shopIds(authentication), status, platform, page, 20).stream()
        .map(order -> toResponse(order, canViewPii))
        .toList();
  }

  private static void requireShopScope(String shopId, List<String> permittedShopIds) {
    if (!permittedShopIds.contains(shopId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Order is outside the assigned shop scope");
    }
  }

  private static List<String> shopIds(JwtAuthenticationToken authentication) {
    var claim = authentication.getToken().getClaimAsStringList("shopIds");
    return claim == null ? List.of() : List.copyOf(claim);
  }

  private static boolean canViewPii(JwtAuthenticationToken authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_PII_VIEW".equals(authority.getAuthority()));
  }

  private static OrderResponse toResponse(SalesOrderView order, boolean canViewPii) {
    return new OrderResponse(
        order.id(),
        order.platform(),
        order.shopId(),
        order.platformOrderId(),
        order.status(),
        order.paidAt(),
        order.lines(),
        order.exceptionCode(),
        canViewPii ? order.receiverCiphertext() : maskPhone(order.receiverCiphertext()));
  }

  private static String maskPhone(String phone) {
    if (phone != null && phone.matches("\\d{11}")) {
      return phone.substring(0, 3) + "****" + phone.substring(7);
    }
    return "******";
  }

  record OrderResponse(
      String id,
      String platform,
      String shopId,
      String platformOrderId,
      String status,
      Instant paidAt,
      List<SalesOrderLineView> lines,
      String exceptionCode,
      String receiverPhone) {
  }
}
