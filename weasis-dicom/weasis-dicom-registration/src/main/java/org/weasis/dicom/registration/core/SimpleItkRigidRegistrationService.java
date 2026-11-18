/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.registration.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import org.dcm4che3.data.Tag;
import org.itk.simple.CenteredTransformInitializerFilter;
import org.itk.simple.Command;
import org.itk.simple.EventEnum;
import org.itk.simple.Image;
import org.itk.simple.ImageRegistrationMethod;
import org.itk.simple.InterpolatorEnum;
import org.itk.simple.SimpleITK;
import org.itk.simple.Transform;
import org.itk.simple.VectorDouble;
import org.itk.simple.VectorUInt32;
import org.itk.simple.VersorRigid3DTransform;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.MediaSeries.MEDIA_POSITION;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.registration.api.RegistrationParameters;
import org.weasis.dicom.registration.api.RegistrationResult;
import org.weasis.dicom.registration.api.VolumeRegistrationService;

/**
 * Implementação OSGi Declarative Services do registro rígido 3D (6 DOF) usando SimpleITK com
 * Informação Mútua de Mattes e pirâmide multirresolução.
 *
 * <p>Referências da API oficial SimpleITK Java:
 *
 * <ul>
 *   <li>https://simpleitk.org/doxygen/latest/html/ImageRegistrationMethod1_2ImageRegistrationMethod1_8java-example.html
 *   <li>https://simpleitk.org/doxygen/latest/html/classitk_1_1simple_1_1ImageRegistrationMethod-members.html
 * </ul>
 */
@Component(service = VolumeRegistrationService.class, immediate = false)
public class SimpleItkRigidRegistrationService implements VolumeRegistrationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SimpleItkRigidRegistrationService.class);

  @Override
  public RegistrationResult registerRigid3D(
      DicomSeries fixedSeries,
      DicomSeries movingSeries,
      RegistrationParameters parameters,
      DoubleConsumer progressListener,
      BooleanSupplier cancelRequested) {

    Objects.requireNonNull(fixedSeries, "fixedSeries não pode ser nula");
    Objects.requireNonNull(movingSeries, "movingSeries não pode ser nula");
    RegistrationParameters params =
        parameters != null ? parameters : RegistrationParameters.multimodalDefaults();

    checkCancellation(cancelRequested);
    SimpleItkNativeLoader.ensureLoaded();

    Image fixedImage = null;
    Image movingImage = null;
    ImageRegistrationMethod registration = null;
    Transform rawCenteredTx = null;
    VersorRigid3DTransform rigidTx = null;
    Command iterationObserver = null;

    try {
      fixedImage = DicomVolumeBuilder.buildFloat32Volume(fixedSeries);
      checkCancellation(cancelRequested);
      movingImage = DicomVolumeBuilder.buildFloat32Volume(movingSeries);
      checkCancellation(cancelRequested);

      // 1. Inicialização do centro de rotação e translação inicial em coordenadas LPS
      CenteredTransformInitializerFilter.OperationModeType initMode =
          params.alignGeometryCenters()
              ? CenteredTransformInitializerFilter.OperationModeType.GEOMETRY
              : CenteredTransformInitializerFilter.OperationModeType.MOMENTS;

      rawCenteredTx =
          SimpleITK.centeredTransformInitializer(
              fixedImage, movingImage, new VersorRigid3DTransform(), initMode);
      rigidTx = new VersorRigid3DTransform(rawCenteredTx);

      // 2. Configuração da métrica de Informação Mútua de Mattes e amostragem estocástica
      registration = new ImageRegistrationMethod();
      registration.setMetricAsMattesMutualInformation(params.histogramBins());
      registration.setMetricSamplingStrategy(
          ImageRegistrationMethod.MetricSamplingStrategyType.RANDOM);
      registration.setMetricSamplingPercentage(params.samplingPercentage());
      registration.setInterpolator(InterpolatorEnum.sitkLinear);

      // 3. Otimizador RegularStepGradientDescent com escalonamento físico automático (radianos vs
      // mm)
      registration.setOptimizerAsRegularStepGradientDescent(
          params.learningRate(),
          params.minStep(),
          params.maxIterations(),
          params.relaxationFactor(),
          1.0e-4,
          ImageRegistrationMethod.EstimateLearningRateType.EachIteration);
      registration.setOptimizerScalesFromPhysicalShift();

      // 4. Pirâmide multirresolução (ex.: shrink 4x -> 2x -> 1x; sigma 2mm -> 1mm -> 0mm)
      registration.setShrinkFactorsPerLevel(new VectorUInt32(params.shrinkFactors()));
      registration.setSmoothingSigmasPerLevel(new VectorDouble(params.smoothingSigmasMm()));
      registration.smoothingSigmasAreSpecifiedInPhysicalUnitsOn();

      // Modifica `rigidTx` in-place (inPlace = true) para extrair matriz 3x3, centro e translação
      // diretamente
      registration.setInitialTransform(rigidTx, true);

      // 5. Observador de iteração para telemetria na UI Swing e suporte a cancelamento cooperativo
      final ImageRegistrationMethod regRef = registration;
      final int totalLevels = params.shrinkFactors().length;
      final int maxIterPerLevel = params.maxIterations();
      iterationObserver =
          new Command() {
            @Override
            public void execute() {
              if (cancelRequested != null && cancelRequested.getAsBoolean()) {
                regRef.stopRegistration();
                return;
              }
              if (progressListener != null) {
                long level = regRef.getCurrentLevel();
                long iter = regRef.getOptimizerIteration();
                double progress =
                    Math.min(
                        0.99,
                        ((double) level + Math.min(1.0, (double) iter / maxIterPerLevel))
                            / totalLevels);
                progressListener.accept(progress);
              }
            }
          };
      registration.addCommand(EventEnum.sitkIterationEvent, iterationObserver);

      // 6. Execução do registro 3D nas threads nativas C++ do ITK
      registration.execute(fixedImage, movingImage).delete();
      java.lang.ref.Reference.reachabilityFence(iterationObserver);

      checkCancellation(cancelRequested);

      double[] fixedToMoving4x4 = toHomogeneousMatrix4x4(rigidTx);
      double[] movingToFixed4x4 = invertRigidMatrix4x4(fixedToMoving4x4);

      if (progressListener != null) {
        progressListener.accept(1.0);
      }

      double metricValue = registration.getMetricValue();
      int totalIterations = (int) registration.getOptimizerIteration();
      String stopDesc = registration.getOptimizerStopConditionDescription();

      LOGGER.info(
          "Registro rígido 3D concluído: MI={} | Iterações={} | Condição={}",
          String.format("%.5f", metricValue),
          totalIterations,
          stopDesc);

      DicomImageElement fixedFirst = fixedSeries.getMedia(MEDIA_POSITION.FIRST, null, null);
      DicomImageElement movingFirst = movingSeries.getMedia(MEDIA_POSITION.FIRST, null, null);

      return new RegistrationResult(
          fixedToMoving4x4,
          movingToFixed4x4,
          metricValue,
          totalIterations,
          stopDesc,
          TagD.getTagValue(fixedSeries, Tag.SeriesInstanceUID, String.class),
          TagD.getTagValue(movingSeries, Tag.SeriesInstanceUID, String.class),
          TagD.getTagValue(fixedFirst, Tag.FrameOfReferenceUID, String.class),
          TagD.getTagValue(movingFirst, Tag.FrameOfReferenceUID, String.class));

    } finally {
      // Liberação determinística da memória nativa C++ antes do Garbage Collector da JVM
      if (registration != null) {
        registration.delete();
      }
      if (iterationObserver != null) {
        iterationObserver.delete();
      }
      if (rigidTx != null) {
        rigidTx.delete();
      }
      if (rawCenteredTx != null) {
        rawCenteredTx.delete();
      }
      if (movingImage != null) {
        movingImage.delete();
      }
      if (fixedImage != null) {
        fixedImage.delete();
      }
    }
  }

  private static void checkCancellation(BooleanSupplier cancelRequested) {
    if (cancelRequested != null && cancelRequested.getAsBoolean()) {
      throw new java.util.concurrent.CancellationException("Registration canceled");
    }
  }

  /**
   * Converte {@link VersorRigid3DTransform} (que rotaciona em torno de {@code center}: {@code p' =
   * R * (p - c) + c + t = R * p + (t + c - R * c)}) em uma matriz homogênea 4x4 row-major.
   */
  public static double[] toHomogeneousMatrix4x4(VersorRigid3DTransform tx) {
    VectorDouble r = tx.getMatrix(); // 9 elementos row-major (3x3)
    VectorDouble c = tx.getCenter(); // 3 elementos (cx, cy, cz)
    VectorDouble t = tx.getTranslation(); // 3 elementos (tx, ty, tz)

    double r00 = r.get(0), r01 = r.get(1), r02 = r.get(2);
    double r10 = r.get(3), r11 = r.get(4), r12 = r.get(5);
    double r20 = r.get(6), r21 = r.get(7), r22 = r.get(8);

    double cx = c.get(0), cy = c.get(1), cz = c.get(2);
    double txMm = t.get(0), tyMm = t.get(1), tzMm = t.get(2);

    double ox = txMm + cx - (r00 * cx + r01 * cy + r02 * cz);
    double oy = tyMm + cy - (r10 * cx + r11 * cy + r12 * cz);
    double oz = tzMm + cz - (r20 * cx + r21 * cy + r22 * cz);

    return new double[] {
      r00, r01, r02, ox, r10, r11, r12, oy, r20, r21, r22, oz, 0.0, 0.0, 0.0, 1.0
    };
  }

  /**
   * Calcula a inversa exata de uma transformação rígida ortonormal 4x4 ({@code R^-1 = R^T}, {@code
   * o^-1 = -R^T * o}), usada no DICOM Spatial Registration Storage (Moving -> Fixed).
   */
  public static double[] invertRigidMatrix4x4(double[] m) {
    double r00 = m[0], r01 = m[1], r02 = m[2], ox = m[3];
    double r10 = m[4], r11 = m[5], r12 = m[6], oy = m[7];
    double r20 = m[8], r21 = m[9], r22 = m[10], oz = m[11];

    double invOx = -(r00 * ox + r10 * oy + r20 * oz);
    double invOy = -(r01 * ox + r11 * oy + r21 * oz);
    double invOz = -(r02 * ox + r12 * oy + r22 * oz);

    return new double[] {
      r00, r10, r20, invOx, r01, r11, r21, invOy, r02, r12, r22, invOz, 0.0, 0.0, 0.0, 1.0
    };
  }
}
