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

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.img.DicomMetaData;
import org.dcm4che3.util.UIDUtils;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.utils.DicomMediaUtils;
import org.weasis.dicom.viewer2d.mpr.DerivedStack;
import org.weasis.dicom.viewer2d.mpr.RawImageIO;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.opencv.data.FileRawImage;
import org.weasis.opencv.data.PlanarImage;

/** A session-local series of moving-volume slices sampled on the fixed acquisition grid. */
final class RegisteredResliceSeries extends DicomSeries {
  private final MediaSeries<DicomImageElement> fixed;
  private final MediaSeries<DicomImageElement> moving;

  private RegisteredResliceSeries(
      MediaSeries<DicomImageElement> fixed, MediaSeries<DicomImageElement> moving, String uid) {
    super(uid);
    this.fixed = fixed;
    this.moving = moving;
  }

  static RegisteredResliceSeries build(
      MediaSeries<DicomImageElement> fixed,
      MediaSeries<DicomImageElement> moving,
      double[] fixedToMoving) {
    var volume = FusionVolumeBuilder.build(moving);
    var reference = moving.getMedia(MEDIA_POSITION.MIDDLE, null, null);
    if (volume == null || volume.isBasic() || reference == null) {
      return null;
    }
    String uid = UIDUtils.createUID();
    var result = new RegisteredResliceSeries(fixed, moving, uid);
    var header = new Attributes();
    DerivedStack.copyMandatoryTags(reference, result);
    result.setTag(TagD.get(Tag.SeriesInstanceUID), uid);
    result.setTag(
        TagD.get(Tag.FrameOfReferenceUID), fixed.getTagValue(TagD.get(Tag.FrameOfReferenceUID)));
    result.setTag(TagD.get(Tag.SeriesDescription), "Registered reconstruction");
    result.setTag(TagW.ExplorerModel, moving.getTagValue(TagW.ExplorerModel));
    DicomMediaUtils.fillAttributes(result.getTagEntrySetIterator(), header);
    header.setString(Tag.SeriesInstanceUID, VR.UI, uid);
    header.setString(Tag.ImageType, VR.CS, "DERIVED", "SECONDARY");
    header.setInt(Tag.SamplesPerPixel, VR.US, 1);
    header.setInt(Tag.BitsAllocated, VR.US, 32);
    header.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    header.setFloat(Tag.FloatPixelData, VR.OF, 0.0f);
    double min = volume.getMinimumAsDouble();
    double max = volume.getMaximumAsDouble();
    if (Double.isFinite(min) && Double.isFinite(max) && max > min) {
      header.setDouble(Tag.WindowCenter, VR.DS, (min + max) * 0.5);
      header.setDouble(Tag.WindowWidth, VR.DS, Math.max(1.0, max - min));
    }
    Attributes movingHeader = reference.getMediaReader().getDicomObject();
    for (int tag : new int[] {Tag.StudyDate, Tag.SeriesDate, Tag.AcquisitionDate}) {
      if (movingHeader != null && movingHeader.containsValue(tag)) {
        header.setString(tag, VR.DA, movingHeader.getString(tag));
      }
    }

    int index = 0;
    for (DicomImageElement source : fixed.copyOfMedias(null, null)) {
      if (source.getRawSliceGeometry() == null) {
        return null;
      }
      var reader = new RegisteredSliceReader(source, volume, fixedToMoving, header, ++index);
      result.addMedia(
          new DicomImageElement(reader, 0) {
            @Override
            public Attributes saveToFile(File output, DicomExportParameters params) {
              return null;
            }
          });
    }
    return index == 0 ? null : result;
  }

  MediaSeries<DicomImageElement> fixed() {
    return fixed;
  }

  MediaSeries<DicomImageElement> moving() {
    return moving;
  }

  private static final class RegisteredSliceReader extends RawImageIO {
    private final DicomImageElement fixedImage;
    private final Volume<?, ?> volume;
    private final double[] matrix;
    private final DicomMetaData metadata;
    private final int width;
    private final int height;

    private RegisteredSliceReader(
        DicomImageElement fixedImage,
        Volume<?, ?> volume,
        double[] matrix,
        Attributes header,
        int index) {
      super(
          new FileRawImage(Path.of("registered-reslice")), fixedImage.getMediaReader().getCodec());
      this.fixedImage = fixedImage;
      this.volume = volume;
      this.matrix = matrix;
      var geometry = fixedImage.getRawSliceGeometry();
      width = (int) geometry.getDimensions().y;
      height = (int) geometry.getDimensions().x;
      var attributes = new Attributes(header);
      attributes.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
      attributes.setInt(Tag.InstanceNumber, VR.IS, index);
      attributes.setInt(Tag.Columns, VR.US, width);
      attributes.setInt(Tag.Rows, VR.US, height);
      for (int tag :
          new int[] {Tag.ImagePositionPatient, Tag.ImageOrientationPatient, Tag.PixelSpacing}) {
        attributes.setDouble(tag, VR.DS, TagD.getTagValue(fixedImage, tag, double[].class));
      }
      for (int tag :
          new int[] {
            Tag.SOPInstanceUID,
            Tag.StudyInstanceUID,
            Tag.SeriesInstanceUID,
            Tag.FrameOfReferenceUID,
            Tag.Modality,
            Tag.InstanceNumber,
            Tag.Columns,
            Tag.Rows,
            Tag.ImagePositionPatient,
            Tag.ImageOrientationPatient,
            Tag.PixelSpacing,
            Tag.PhotometricInterpretation,
            Tag.WindowCenter,
            Tag.WindowWidth,
            Tag.StudyDate,
            Tag.SeriesDate,
            Tag.AcquisitionDate,
            Tag.BitsAllocated
          }) {
        TagD.get(tag).readValue(attributes, this);
      }
      setTag(TagW.PatientPseudoUID, fixedImage.getTagValue(TagW.PatientPseudoUID));
      setTag(TagW.MonoChrome, Boolean.TRUE);
      DicomMediaUtils.computeSlicePosition(this);
      metadata = new DicomMetaData(attributes, UID.ExplicitVRLittleEndian);
    }

    @Override
    public URI getUri() {
      return URI.create("data:" + getTagValue(TagD.get(Tag.SOPInstanceUID)));
    }

    @Override
    public DicomMetaData getDicomMetaData() {
      return metadata;
    }

    @Override
    public Attributes getDicomObject() {
      return metadata.getDicomObject();
    }

    @Override
    public File getDicomFile() {
      return null;
    }

    @Override
    public PlanarImage getImageFragment(MediaElement media) {
      return FusionVolumeResampler.resampleRegisteredValues(
          volume, fixedImage.getRawSliceGeometry(), width, height, matrix);
    }
  }
}
