/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d.fusion;

import java.util.Objects;
import java.util.Set;
import org.dcm4che3.data.Tag;
import org.joml.Vector3d;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeries.MEDIA_POSITION;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.SortSeriesStack;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.geometry.GeometryOfSlice;
import org.weasis.dicom.explorer.DicomModel;

/** Checks patient identity, volume geometry, and alignment for fusion. */
public final class FusionCompatibility {

  /** Volumetric modalities supported as either base or registered/co-located overlay. */
  private static final Set<String> VOLUMETRIC_MODALITIES =
      Set.of("CT", "MR", "PT", "NM", "RTDOSE"); // NON-NLS

  private FusionCompatibility() {}

  /** Returns {@code true} if {@code candidate} can be fused onto {@code base}. */
  public static boolean isCompatible(
      MediaSeries<DicomImageElement> base, MediaSeries<DicomImageElement> candidate) {
    if (!isRegistrationCandidate(base, candidate)) {
      return false;
    }
    String frame = TagD.getTagValue(base, Tag.FrameOfReferenceUID, String.class);
    return FusionController.hasRegisteredTransform(base, candidate)
        || (frame != null
            && !frame.isBlank()
            && frame.equals(TagD.getTagValue(candidate, Tag.FrameOfReferenceUID, String.class)))
        || (sameStudy(base, candidate)
            && overlaps(boundingBoxLps(base), boundingBoxLps(candidate)));
  }

  /** Returns whether the volumes can be offered for registration. */
  public static boolean isRegistrationCandidate(
      MediaSeries<DicomImageElement> base, MediaSeries<DicomImageElement> candidate) {
    if (base == null || candidate == null || base == candidate) {
      return false;
    }
    String baseMod = TagD.getTagValue(base, Tag.Modality, String.class);
    String candidateMod = TagD.getTagValue(candidate, Tag.Modality, String.class);
    return baseMod != null
        && candidateMod != null
        && VOLUMETRIC_MODALITIES.contains(baseMod)
        && VOLUMETRIC_MODALITIES.contains(candidateMod)
        && samePatient(base, candidate)
        && boundingBoxLps(base) != null
        && boundingBoxLps(candidate) != null;
  }

  private static boolean sameStudy(
      MediaSeries<DicomImageElement> base, MediaSeries<DicomImageElement> candidate) {
    String study =
        TagD.getTagValue(
            base.getMedia(MEDIA_POSITION.FIRST, null, null), Tag.StudyInstanceUID, String.class);
    return study != null
        && !study.isBlank()
        && study.equals(
            TagD.getTagValue(
                candidate.getMedia(MEDIA_POSITION.FIRST, null, null),
                Tag.StudyInstanceUID,
                String.class));
  }

  private static boolean samePatient(
      MediaSeries<DicomImageElement> base, MediaSeries<DicomImageElement> candidate) {
    String patient = DicomModel.getPatientPseudoUID(base);
    if (patient == null
        || patient.isBlank()
        || !patient.equals(DicomModel.getPatientPseudoUID(candidate))) {
      return false;
    }
    DicomImageElement first = base.getMedia(MEDIA_POSITION.FIRST, null, null);
    DicomImageElement second = candidate.getMedia(MEDIA_POSITION.FIRST, null, null);
    String id = TagD.getTagValue(first, Tag.PatientID, String.class);
    return id != null
        && !id.isBlank()
        && id.equals(TagD.getTagValue(second, Tag.PatientID, String.class))
        && Objects.equals(
            TagD.getTagValue(first, Tag.IssuerOfPatientID, String.class),
            TagD.getTagValue(second, Tag.IssuerOfPatientID, String.class));
  }

  /**
   * Axis-aligned bounding box of the series volume in patient (LPS) mm as {@code
   * {minX,minY,minZ,maxX,maxY,maxZ}}, or {@code null} when the series is not a volume with valid
   * geometry.
   */
  private static double[] boundingBoxLps(MediaSeries<DicomImageElement> series) {
    if (series.size(null) < 2) {
      return null;
    }
    DicomImageElement first =
        series.getMedia(MEDIA_POSITION.FIRST, null, SortSeriesStack.slicePosition);
    DicomImageElement last =
        series.getMedia(MEDIA_POSITION.LAST, null, SortSeriesStack.slicePosition);
    if (first == null || last == null) {
      return null;
    }
    GeometryOfSlice g1 = first.getSliceGeometry();
    GeometryOfSlice g2 = last.getSliceGeometry();
    if (g1 == null || g2 == null) {
      return null;
    }
    double[] box = {
      Double.MAX_VALUE,
      Double.MAX_VALUE,
      Double.MAX_VALUE,
      -Double.MAX_VALUE,
      -Double.MAX_VALUE,
      -Double.MAX_VALUE
    };
    accumulateCorners(g1, box);
    accumulateCorners(g2, box);
    return box;
  }

  /** Expands {@code box} to include the four in-plane corners of the given slice. */
  private static void accumulateCorners(GeometryOfSlice g, double[] box) {
    Vector3d tlhc = g.getTLHC();
    Vector3d row = g.getRow();
    Vector3d col = g.getColumn();
    Vector3d spacing = g.getVoxelSpacing();
    Vector3d dim = g.getDimensions();
    if (tlhc == null || row == null || col == null || spacing == null || dim == null) {
      return;
    }
    double widthMm = spacing.x * dim.y; // extent along the row direction (columns)
    double heightMm = spacing.y * dim.x; // extent along the column direction (rows)
    for (int a = 0; a <= 1; a++) {
      for (int b = 0; b <= 1; b++) {
        double x = tlhc.x + row.x * widthMm * a + col.x * heightMm * b;
        double y = tlhc.y + row.y * widthMm * a + col.y * heightMm * b;
        double z = tlhc.z + row.z * widthMm * a + col.z * heightMm * b;
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
      }
    }
  }

  private static boolean overlaps(double[] a, double[] b) {
    return a[0] <= b[3]
        && a[3] >= b[0]
        && a[1] <= b[4]
        && a[4] >= b[1]
        && a[2] <= b[5]
        && a[5] >= b[2];
  }
}
