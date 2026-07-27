package com.company.erp.pinduoduo.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.erp.pinduoduo.config.ProductionGate.Operation;
import com.company.erp.pinduoduo.config.ProductionGate.ProductionDisabledException;
import org.junit.jupiter.api.Test;

class ProductionGateTest {
  @Test
  void disabledGateBlocksProductionBusinessReads() {
    var gate = new ProductionGate(false);

    assertThatThrownBy(() -> gate.requireReadAllowed(Operation.ORDER_PULL))
        .isInstanceOf(ProductionDisabledException.class)
        .hasMessageContaining("ORDER_PULL");
  }

  @Test
  void disabledGateAllowsHealthChecks() {
    var gate = new ProductionGate(false);

    assertThatCode(() -> gate.requireReadAllowed(Operation.HEALTH_CHECK))
        .doesNotThrowAnyException();
  }

  @Test
  void disabledGateBlocksPlatformSideEffects() {
    var gate = new ProductionGate(false);

    assertThatThrownBy(() -> gate.requireSideEffectAllowed(Operation.ORDER_PULL))
        .isInstanceOf(ProductionDisabledException.class);
  }

  @Test
  void enabledGateAllowsProductionOperations() {
    var gate = new ProductionGate(true);

    assertThatCode(() -> gate.requireReadAllowed(Operation.ORDER_PULL))
        .doesNotThrowAnyException();
    assertThatCode(() -> gate.requireSideEffectAllowed(Operation.ORDER_PULL))
        .doesNotThrowAnyException();
  }
}
