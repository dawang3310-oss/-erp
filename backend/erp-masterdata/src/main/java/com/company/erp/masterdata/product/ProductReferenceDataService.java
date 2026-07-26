package com.company.erp.masterdata.product;

import java.util.List;

public interface ProductReferenceDataService {
  List<ReferenceItem> findBrands(String query);

  ReferenceItem createBrand(String name);

  List<CategoryItem> findCategories(String query);

  CategoryItem createCategory(String name, String parentId);

  record ReferenceItem(String id, String name) {
  }

  record CategoryItem(String id, String name, String parentId, String path) {
  }
}
