package com.company.erp.masterdata.importing;

import com.company.erp.masterdata.importing.ProductJobViews.ImportJobView;
import com.company.erp.masterdata.importing.ProductJobViews.ExportJobView;
import com.company.erp.masterdata.product.ProductCommands.AuditActor;
import com.company.erp.masterdata.product.ProductViews.ProductFilter;
import java.io.InputStream;

public interface ProductImportService {
  String createPreflightJob(
      InputStream workbook,
      long size,
      String filename,
      AuditActor actor);

  void executePreflight(String jobId);

  void confirm(String jobId, String idempotencyKey, AuditActor actor);

  ImportJobView getImportJob(String jobId);

  void executeConfirmedJob(String jobId);

  String createExport(
      ProductFilter filter,
      String idempotencyKey,
      AuditActor actor);

  ExportJobView getExportJob(String jobId);

  void executeExportJob(String jobId);
}
