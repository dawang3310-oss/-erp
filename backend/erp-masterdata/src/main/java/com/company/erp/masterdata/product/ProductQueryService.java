package com.company.erp.masterdata.product;

import com.company.erp.masterdata.product.ProductViews.ProductDetail;
import com.company.erp.masterdata.product.ProductViews.ProductFilter;
import com.company.erp.masterdata.product.ProductViews.ProductPage;
import java.util.Optional;

public interface ProductQueryService {
  Optional<ProductDetail> get(String id);

  ProductPage list(ProductFilter filter, int page, int size);
}
