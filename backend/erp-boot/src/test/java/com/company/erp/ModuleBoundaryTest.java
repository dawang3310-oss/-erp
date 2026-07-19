package com.company.erp;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;

class ModuleBoundaryTest {
  @Test
  void application_entry_point_exists() {
    assertThatCode(() -> Class.forName("com.company.erp.ErpApplication"))
        .doesNotThrowAnyException();
  }

  @Test
  void modules_do_not_access_order_internals() {
    var classes = new ClassFileImporter().importPackages("com.company.erp");
    noClasses().that().resideOutsideOfPackage("..order..")
        .should().dependOnClassesThat().resideInAPackage("..order.internal..")
        .allowEmptyShould(true)
        .check(classes);
  }
}
