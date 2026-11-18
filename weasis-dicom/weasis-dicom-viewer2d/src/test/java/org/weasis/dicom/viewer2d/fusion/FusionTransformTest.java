/*
 * Copyright (c) 2025 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d.fusion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.geometry.GeometryOfSlice;

class FusionTransformTest {
  @Test
  void acceptsRigidRotationAndTranslation() {
    assertDoesNotThrow(
        () ->
            FusionController.validateTransform(
                new double[] {0, -1, 0, 10, 1, 0, 0, 20, 0, 0, 1, 30, 0, 0, 0, 1}));
  }

  @Test
  void rejectsMalformedAndSingularMatrices() {
    assertThrows(
        IllegalArgumentException.class, () -> FusionController.validateTransform(new double[15]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FusionController.validateTransform(
                new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FusionController.validateTransform(
                new double[] {1, 0, 0, Double.NaN, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FusionController.validateTransform(
                new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1}));
  }

  @Test
  @SuppressWarnings("unchecked")
  void followsRegisteredSliceInBothDirections() {
    MediaSeries<DicomImageElement> fixed = mock(MediaSeries.class);
    MediaSeries<DicomImageElement> moving = mock(MediaSeries.class);
    DicomImageElement fixedImage = imageAt(10);
    DicomImageElement movingImage = imageAt(17);
    when(fixed.getMedia(0, null, null)).thenReturn(fixedImage);
    when(moving.getMedia(0, null, null)).thenReturn(movingImage);
    when(fixed.getNearestImage(10, 0, null, null)).thenReturn(fixedImage);
    when(moving.getNearestImage(17, 0, null, null)).thenReturn(movingImage);
    try (var registry = mockStatic(FusionController.class)) {
      registry
          .when(() -> FusionController.getRegisteredTransform(fixed, moving))
          .thenReturn(new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 7, 0, 0, 0, 1});
      assertSame(
          movingImage,
          FusionSliceMatcher.findRegisteredSlice(fixedImage, fixed, moving, null, null));
      assertSame(
          fixedImage,
          FusionSliceMatcher.findRegisteredSlice(movingImage, moving, fixed, null, null));
    }
  }

  private static DicomImageElement imageAt(double z) {
    var image = mock(DicomImageElement.class);
    when(image.getSliceGeometry())
        .thenReturn(
            new GeometryOfSlice(
                new Vector3d(1, 0, 0),
                new Vector3d(0, 1, 0),
                new Vector3d(0, 0, z),
                new Vector3d(0.7, 1.2, 2.4),
                2.4,
                new Vector3d(128, 128, 1)));
    return image;
  }
}
