/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.streamprocessor;

import io.zeebe.engine.processing.streamprocessor.writers.ReprocessingRecord;

public final class InconsistentReprocessingException extends RuntimeException {

  private static final String FAILURE_MESSAGE =
      "Reprocessing issue detected! Restore the data from a backup and follow the recommended upgrade procedure.";

  public InconsistentReprocessingException(
      final String message, final TypedRecord<?> actualRecord) {
    super(
        String.format(
            FAILURE_MESSAGE + " [cause: \"%s\", log-stream-record: %s]",
            message,
            actualRecord.toJson()));
  }

  public InconsistentReprocessingException(
      final String message,
      final TypedRecord<?> actualRecord,
      final ReprocessingRecord reprocessingRecord) {
    super(
        String.format(
            FAILURE_MESSAGE + " [cause: \"%s\", log-stream-record: %s, reprocessing-record: %s]",
            message,
            actualRecord.toJson(),
            reprocessingRecord.toString()));
  }
}
