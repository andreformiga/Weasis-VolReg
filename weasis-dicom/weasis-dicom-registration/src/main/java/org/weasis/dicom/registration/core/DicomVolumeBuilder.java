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

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.dcm4che3.data.Tag;
import org.itk.simple.Image;
import org.itk.simple.PixelIDValueEnum;
import org.itk.simple.VectorDouble;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.weasis.core.api.media.data.MediaSeries.MEDIA_POSITION;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.opencv.data.PlanarImage;

/**
 * Reconstrói um {@link org.itk.simple.Image} 3D (sitkFloat32) em espaço paciente DICOM (LPS)
 * diretamente a partir de uma {@link DicomSeries} decodificada pelo Weasis, sem gravar arquivos em
 * disco.
 *
 * <p>Utiliza o acesso zero-copy {@code Image.getBufferAsByteBuffer()} documentado em:
 * https://simpleitk.readthedocs.io/en/master/link_JavaGetSetBuffer_docs.html
 */
public final class DicomVolumeBuilder {

  private static final int MIN_SLICES = 5;
  private static final double SLICE_SPACING_TOLERANCE_MM = 0.25;

  private DicomVolumeBuilder() {}

  private record PositionedSlice(DicomImageElement element, double[] positionLps, double projMm) {}

  public static Image buildFloat32Volume(DicomSeries series) {
    List<DicomImageElement> rawMedias = series.copyOfMedias(null, null);
    if (rawMedias == null || rawMedias.size() < MIN_SLICES) {
      throw new IllegalArgumentException(
          "A série DICOM precisa ter ao menos " + MIN_SLICES + " fatias para registro 3D.");
    }

    DicomImageElement refElement = series.getMedia(MEDIA_POSITION.FIRST, null, null);
    double[] orientation =
        TagD.getTagValue(refElement, Tag.ImageOrientationPatient, double[].class);
    double[] pixelSpacing = TagD.getTagValue(refElement, Tag.PixelSpacing, double[].class);
    if (!validVector(orientation, 6)
        || !validVector(pixelSpacing, 2)
        || pixelSpacing[0] <= 0
        || pixelSpacing[1] <= 0) {
      throw new IllegalArgumentException(
          "Série DICOM sem geometria espacial obrigatória (ImageOrientationPatient / PixelSpacing).");
    }

    // Cossenos diretores das linhas (eixo X da imagem) e colunas (eixo Y da imagem) em LPS
    double[] rowDir = new double[] {orientation[0], orientation[1], orientation[2]};
    double[] colDir = new double[] {orientation[3], orientation[4], orientation[5]};
    if (Math.abs(dotProduct(rowDir, rowDir) - 1) > 1e-4
        || Math.abs(dotProduct(colDir, colDir) - 1) > 1e-4
        || Math.abs(dotProduct(rowDir, colDir)) > 1e-4) {
      throw new IllegalArgumentException("DICOM image directions must be orthonormal");
    }
    double[] sliceNormal = crossProduct(rowDir, colDir);
    normalizeInPlace(sliceNormal);

    List<PositionedSlice> slices = new ArrayList<>(rawMedias.size());
    for (DicomImageElement media : rawMedias) {
      double[] pos = TagD.getTagValue(media, Tag.ImagePositionPatient, double[].class);
      if (media.getMediaReader().getMediaElementNumber() > 1) {
        throw new IllegalArgumentException("Multiframe DICOM registration is unsupported");
      }
      if (!validVector(pos, 3)
          || !matches(
              orientation, TagD.getTagValue(media, Tag.ImageOrientationPatient, double[].class))
          || !matches(pixelSpacing, TagD.getTagValue(media, Tag.PixelSpacing, double[].class))) {
        throw new IllegalArgumentException("Inconsistent DICOM slice geometry");
      }
      slices.add(new PositionedSlice(media, pos, dotProduct(pos, sliceNormal)));
    }

    // Ordena do início ao fim ao longo da normal (garantindo sistema destrógiro em LPS)
    slices.sort(Comparator.comparingDouble(PositionedSlice::projMm));

    double totalSpanMm = slices.get(slices.size() - 1).projMm() - slices.get(0).projMm();
    double meanSliceSpacing = totalSpanMm / (slices.size() - 1);
    if (meanSliceSpacing <= 1.0e-5) {
      throw new IllegalArgumentException(
          "Espaçamento entre fatias inválido ou fatias colocalizadas.");
    }
    for (int i = 1; i < slices.size(); i++) {
      double step = slices.get(i).projMm() - slices.get(i - 1).projMm();
      double alongRow =
          dotProduct(slices.get(i).positionLps(), rowDir)
              - dotProduct(slices.getFirst().positionLps(), rowDir);
      double alongColumn =
          dotProduct(slices.get(i).positionLps(), colDir)
              - dotProduct(slices.getFirst().positionLps(), colDir);
      if (Math.hypot(alongRow, alongColumn) > 0.01) {
        throw new IllegalArgumentException("Tilted DICOM slice stacks are unsupported");
      }
      if (step <= 1e-5 || Math.abs(step - meanSliceSpacing) > SLICE_SPACING_TOLERANCE_MM) {
        throw new IllegalArgumentException(
            String.format(
                "Espaçamento Z não uniforme detectado entre fatias %d e %d (%.3f mm vs média %.3f mm).",
                i - 1, i, step, meanSliceSpacing));
      }
    }

    PlanarImage firstImg = slices.get(0).element().getImage(null);
    if (firstImg == null) {
      throw new IllegalStateException("Não foi possível decodificar os pixels da primeira fatia.");
    }
    int width = firstImg.width();
    int height = firstImg.height();
    int depth = slices.size();

    Image itkImage = new Image(width, height, depth, PixelIDValueEnum.sitkFloat32);

    try {
      // ATENÇÃO: Em DICOM, PixelSpacing = [RowSpacing (dy), ColumnSpacing (dx)].
      // Em ITK, Spacing = [spacingX (colunas), spacingY (linhas), spacingZ (fatias)].
      double spacingX = pixelSpacing[1];
      double spacingY = pixelSpacing[0];
      itkImage.setSpacing(new VectorDouble(new double[] {spacingX, spacingY, meanSliceSpacing}));
      itkImage.setOrigin(new VectorDouble(slices.get(0).positionLps()));

      // Matriz de direção 3x3 em ITK: colunas são os eixos [rowDir, colDir, sliceNormal],
      // serializada row-major
      double[] directionRowMajor =
          new double[] {
            rowDir[0], colDir[0], sliceNormal[0],
            rowDir[1], colDir[1], sliceNormal[1],
            rowDir[2], colDir[2], sliceNormal[2]
          };
      itkImage.setDirection(new VectorDouble(directionRowMajor));

      // Preenchimento zero-copy no buffer nativo do SimpleITK convertendo pixels para valores
      // físicos (ex.: HU)
      FloatBuffer dstBuffer =
          itkImage.getBufferAsByteBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer();
      dstBuffer.rewind();
      float[] sliceFloatRow = new float[width * height];

      for (PositionedSlice slice : slices) {
        DicomImageElement dicomSlice = slice.element();
        PlanarImage planar = dicomSlice.getImage(null);
        if (planar == null || planar.width() != width || planar.height() != height) {
          throw new IllegalStateException("Dimensões inconsistentes ao ler fatia da série DICOM.");
        }

        Double slopeObj = TagD.getTagValue(dicomSlice, Tag.RescaleSlope, Double.class);
        Double interceptObj = TagD.getTagValue(dicomSlice, Tag.RescaleIntercept, Double.class);
        double slope = slopeObj != null ? slopeObj : 1.0;
        double intercept = interceptObj != null ? interceptObj : 0.0;

        Mat floatMat = new Mat();
        try {
          planar.toMat().convertTo(floatMat, CvType.CV_32FC1, slope, intercept);
          floatMat.get(0, 0, sliceFloatRow);
          dstBuffer.put(sliceFloatRow);
        } finally {
          floatMat.release();
        }
      }

      return itkImage;
    } catch (RuntimeException | Error e) {
      itkImage.delete();
      throw e;
    }
  }

  private static boolean validVector(double[] values, int size) {
    return values != null
        && values.length == size
        && Arrays.stream(values).allMatch(Double::isFinite);
  }

  private static boolean matches(double[] reference, double[] values) {
    if (!validVector(values, reference.length)) {
      return false;
    }
    for (int i = 0; i < reference.length; i++) {
      if (Math.abs(reference[i] - values[i]) > 1e-4) {
        return false;
      }
    }
    return true;
  }

  private static double dotProduct(double[] a, double[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static double[] crossProduct(double[] a, double[] b) {
    return new double[] {
      a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
    };
  }

  private static void normalizeInPlace(double[] v) {
    double norm = Math.sqrt(dotProduct(v, v));
    if (norm > 1.0e-9) {
      v[0] /= norm;
      v[1] /= norm;
      v[2] /= norm;
    }
  }
}
