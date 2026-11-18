/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.registration.api;

import java.util.Objects;

/**
 * Resultado imutável do registro rígido 3D no sistema de coordenadas paciente DICOM (LPS, em mm).
 *
 * <p>Convenção de direção das matrizes homogêneas 4x4 (armazenadas em ordem row-major, length =
 * 16):
 *
 * <ul>
 *   <li>{@code fixedToMovingLps4x4}: mapeia um ponto físico {@code p_fixed} (mm, LPS) para {@code
 *       p_moving} (mm, LPS). Esta é a matriz direta do ITK usada na reamostragem reversa (backward
 *       trilinear mapping) nos planos de Image Fusion e MPR do Weasis.
 *   <li>{@code movingToFixedLps4x4}: inversa analítica ({@code p_moving -> p_fixed}), exigida pelo
 *       atributo DICOM {@code FrameOfReferenceTransformationMatrix (3006,00C6)} em objetos Spatial
 *       Registration Storage ({@code 1.2.840.10008.5.1.4.1.1.66.1}).
 * </ul>
 */
public record RegistrationResult(
    double[] fixedToMovingLps4x4,
    double[] movingToFixedLps4x4,
    double finalMetricValue,
    int iterations,
    String stopConditionDescription,
    String fixedSeriesInstanceUid,
    String movingSeriesInstanceUid,
    String fixedFrameOfReferenceUid,
    String movingFrameOfReferenceUid) {

  public RegistrationResult {
    Objects.requireNonNull(fixedToMovingLps4x4, "fixedToMovingLps4x4 não pode ser nulo");
    Objects.requireNonNull(movingToFixedLps4x4, "movingToFixedLps4x4 não pode ser nulo");
    if (fixedToMovingLps4x4.length != 16 || movingToFixedLps4x4.length != 16) {
      throw new IllegalArgumentException(
          "Matrizes homogêneas 4x4 devem conter exatamente 16 valores");
    }
    fixedToMovingLps4x4 = fixedToMovingLps4x4.clone();
    movingToFixedLps4x4 = movingToFixedLps4x4.clone();
  }

  @Override
  public double[] fixedToMovingLps4x4() {
    return fixedToMovingLps4x4.clone();
  }

  @Override
  public double[] movingToFixedLps4x4() {
    return movingToFixedLps4x4.clone();
  }

  /** Transforma uma coordenada física LPS (x, y, z em mm) do volume fixo para o volume móvel. */
  public double[] mapFixedPointToMovingLps(double xMm, double yMm, double zMm) {
    double[] m = fixedToMovingLps4x4;
    return new double[] {
      m[0] * xMm + m[1] * yMm + m[2] * zMm + m[3],
      m[4] * xMm + m[5] * yMm + m[6] * zMm + m[7],
      m[8] * xMm + m[9] * yMm + m[10] * zMm + m[11]
    };
  }
}
