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
 * Hiperparâmetros do registro rígido 3D multirresolução por Informação Mútua de Mattes no
 * SimpleITK.
 *
 * @param histogramBins Número de bins do histograma conjunto de Mattes (padrão recomendado: 50).
 * @param samplingPercentage Fração estocástica de voxels amostrados por nível no intervalo (0.0,
 *     1.0].
 * @param learningRate Taxa inicial de passo do otimizador RegularStepGradientDescent.
 * @param minStep Passo mínimo para critério de parada de convergência.
 * @param maxIterations Limite máximo de iterações por nível da pirâmide.
 * @param relaxationFactor Fator de redução do passo ao inverter direção do gradiente (0.0, 1.0).
 * @param shrinkFactors Fatores isotrópicos de subamostragem por nível (ex.: {4, 2, 1}).
 * @param smoothingSigmasMm Sigmas gaussianos em milímetros físicos por nível (ex.: {2.0, 1.0,
 *     0.0}).
 * @param alignGeometryCenters Se true, inicializa pelo centro geométrico (GEOMETRY); se false, usa
 *     centro de massa de intensidades (MOMENTS).
 */
public record RegistrationParameters(
    int histogramBins,
    double samplingPercentage,
    double learningRate,
    double minStep,
    int maxIterations,
    double relaxationFactor,
    long[] shrinkFactors,
    double[] smoothingSigmasMm,
    boolean alignGeometryCenters) {

  public RegistrationParameters {
    if (histogramBins < 8) {
      throw new IllegalArgumentException("histogramBins deve ser >= 8");
    }
    if (samplingPercentage <= 0.0 || samplingPercentage > 1.0) {
      throw new IllegalArgumentException("samplingPercentage deve estar em (0.0, 1.0]");
    }
    Objects.requireNonNull(shrinkFactors, "shrinkFactors não pode ser nulo");
    Objects.requireNonNull(smoothingSigmasMm, "smoothingSigmasMm não pode ser nulo");
    if (shrinkFactors.length == 0 || shrinkFactors.length != smoothingSigmasMm.length) {
      throw new IllegalArgumentException(
          "shrinkFactors e smoothingSigmasMm devem ter o mesmo tamanho não vazio");
    }
    shrinkFactors = shrinkFactors.clone();
    smoothingSigmasMm = smoothingSigmasMm.clone();
  }

  /** Preset clínico padrão para fusão multimodal CT-MR, PET-MR ou MR T1-T2/FLAIR. */
  public static RegistrationParameters multimodalDefaults() {
    return new RegistrationParameters(
        50,
        0.15,
        2.0,
        1.0e-4,
        200,
        0.5,
        new long[] {4L, 2L, 1L},
        new double[] {2.0, 1.0, 0.0},
        true);
  }
}
