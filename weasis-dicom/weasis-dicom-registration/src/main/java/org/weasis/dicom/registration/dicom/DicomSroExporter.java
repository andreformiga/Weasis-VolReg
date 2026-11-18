/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.registration.dicom;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;
import org.weasis.core.api.media.data.MediaSeries.MEDIA_POSITION;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.registration.api.RegistrationResult;

/**
 * Exporta o resultado do alinhamento como um arquivo padrão DICOM Spatial Registration Storage (SOP
 * Class UID {@code 1.2.840.10008.5.1.4.1.1.66.1}), compatível com envio PACS (C-STORE / STOW-RS).
 *
 * <p>Especificação IOD: https://dicom.nema.org/dicom/2013/output/chtml/part04/sect_i.4.html
 */
public final class DicomSroExporter {

  private DicomSroExporter() {}

  public static File exportSpatialRegistrationObject(
      DicomSeries fixedSeries, DicomSeries movingSeries, RegistrationResult result, File outputFile)
      throws IOException {

    DicomImageElement fixedFirst = fixedSeries.getMedia(MEDIA_POSITION.FIRST, null, null);
    Attributes dataset = new Attributes();

    // Metadados demográficos e de estudo herdados da série de referência (Fixed)
    dataset.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
    dataset.setString(Tag.SOPClassUID, VR.UI, UID.SpatialRegistrationStorage);
    dataset.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
    dataset.setString(Tag.Modality, VR.CS, "REG");
    dataset.setDate(Tag.InstanceCreationDateAndTime, new Date());
    dataset.setDate(Tag.ContentDateAndTime, new Date());

    copyStringTag(fixedFirst, dataset, Tag.PatientName, VR.PN);
    copyStringTag(fixedFirst, dataset, Tag.PatientID, VR.LO);
    copyStringTag(fixedFirst, dataset, Tag.PatientBirthDate, VR.DA);
    copyStringTag(fixedFirst, dataset, Tag.PatientSex, VR.CS);
    copyStringTag(fixedFirst, dataset, Tag.StudyInstanceUID, VR.UI);
    copyStringTag(fixedFirst, dataset, Tag.StudyID, VR.SH);

    dataset.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
    dataset.setString(Tag.SeriesNumber, VR.IS, "990");
    dataset.setString(Tag.InstanceNumber, VR.IS, "1");
    dataset.setString(Tag.SeriesDescription, VR.LO, "Rigid 3D Registration (SimpleITK Mattes MI)");
    dataset.setString(Tag.ContentLabel, VR.CS, "RIGID_REG");
    dataset.setString(
        Tag.FrameOfReferenceUID,
        VR.UI,
        result.fixedFrameOfReferenceUid() != null
            ? result.fixedFrameOfReferenceUid()
            : UIDUtils.createUID());

    Sequence regSeq = dataset.newSequence(Tag.RegistrationSequence, 2);

    // Item 1: Identidade no FrameOfReference da série fixa
    regSeq.add(
        buildRegistrationItem(
            result.fixedFrameOfReferenceUid(),
            new double[] {
              1, 0, 0, 0,
              0, 1, 0, 0,
              0, 0, 1, 0,
              0, 0, 0, 1
            }));

    // Item 2: Transformação rígida mapeando coordenadas da série móvel -> série fixa (LPS)
    regSeq.add(
        buildRegistrationItem(result.movingFrameOfReferenceUid(), result.movingToFixedLps4x4()));

    Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
    try (DicomOutputStream dos = new DicomOutputStream(outputFile)) {
      dos.writeDataset(fmi, dataset);
    }
    return outputFile;
  }

  private static Attributes buildRegistrationItem(String frameOfRefUid, double[] matrix4x4) {
    Attributes regItem = new Attributes();
    regItem.setString(
        Tag.FrameOfReferenceUID,
        VR.UI,
        frameOfRefUid != null ? frameOfRefUid : UIDUtils.createUID());

    Sequence matrixRegSeq = regItem.newSequence(Tag.MatrixRegistrationSequence, 1);
    Attributes matrixRegItem = new Attributes();
    Sequence matrixSeq = matrixRegItem.newSequence(Tag.MatrixSequence, 1);

    Attributes matrixItem = new Attributes();
    matrixItem.setString(Tag.FrameOfReferenceTransformationMatrixType, VR.CS, "RIGID");
    matrixItem.setDouble(Tag.FrameOfReferenceTransformationMatrix, VR.DS, matrix4x4);
    matrixSeq.add(matrixItem);

    matrixRegSeq.add(matrixRegItem);
    return regItem;
  }

  private static void copyStringTag(DicomImageElement source, Attributes target, int tag, VR vr) {
    String val = TagD.getTagValue(source, tag, String.class);
    target.setString(tag, vr, val != null ? val : "");
  }
}
