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

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import org.weasis.dicom.codec.DicomSeries;

/** Serviço OSGi para registro volumétrico 3D entre duas séries DICOM carregadas no Weasis. */
public interface VolumeRegistrationService {

  /**
   * Executa o registro rígido 3D (6 graus de liberdade) entre {@code fixedSeries} e {@code
   * movingSeries}.
   *
   * @param fixedSeries Série anatômica de referência (espaço alvo).
   * @param movingSeries Série móvel a ser alinhada sobre a referência.
   * @param parameters Parâmetros da métrica de Informação Mútua e pirâmide multirresolução.
   * @param progressListener Callback opcional recebendo progresso estimado em [0.0, 1.0].
   * @param cancelRequested Supplier opcional consultado a cada iteração para abortar o otimizador.
   * @return Matrizes LPS 4x4 direta/inversa e métricas finais de convergência.
   */
  RegistrationResult registerRigid3D(
      DicomSeries fixedSeries,
      DicomSeries movingSeries,
      RegistrationParameters parameters,
      DoubleConsumer progressListener,
      BooleanSupplier cancelRequested);
}
