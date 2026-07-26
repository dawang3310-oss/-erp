package com.company.erp.pinduoduo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ConnectorBoundaryTest {
  @Test
  void connectorDoesNotDependOnErpImplementationModules() {
    var classes = new ClassFileImporter().importPackages("com.company.erp.pinduoduo");

    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.company.erp.order..",
            "com.company.erp.inventory..",
            "com.company.erp.fulfillment..",
            "com.company.erp.masterdata..")
        .check(classes);
  }
}
