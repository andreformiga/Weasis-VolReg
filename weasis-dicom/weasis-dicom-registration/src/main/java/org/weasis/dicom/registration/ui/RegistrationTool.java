/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.registration.ui;

import bibliothek.gui.dock.common.CLocation;
import java.awt.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import net.miginfocom.swing.MigLayout;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.registration.api.RegistrationParameters;
import org.weasis.dicom.registration.api.RegistrationResult;
import org.weasis.dicom.registration.api.VolumeRegistrationService;
import org.weasis.dicom.viewer2d.EventManager;
import org.weasis.dicom.viewer2d.fusion.FusionCompatibility;
import org.weasis.dicom.viewer2d.fusion.FusionController;

/**
 * Painel lateral acoplável (DockableTool) integrado ao visualizador 2D/MPR do Weasis. Executa o
 * registro rígido 3D em background fora da Event Dispatch Thread (EDT).
 */
public class RegistrationTool extends PluginTool {

  private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationTool.class);
  public static final String BUTTON_NAME = "Registro 3D (SimpleITK)";

  private final VolumeRegistrationService registrationService;

  private final JComboBox<DicomSeries> fixedCombo = new JComboBox<>();
  private final JComboBox<DicomSeries> movingCombo = new JComboBox<>();
  private final JSpinner binsSpinner = new JSpinner(new SpinnerNumberModel(50, 16, 256, 10));
  private final JSpinner samplingSpinner = new JSpinner(new SpinnerNumberModel(15, 1, 100, 5));
  private final JSpinner maxIterSpinner = new JSpinner(new SpinnerNumberModel(200, 20, 1000, 25));
  private final JCheckBox geometryInitCheck =
      new JCheckBox("Inicializar pelo centro geométrico", true);

  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JLabel statusLabel = new JLabel("Selecione as séries Fixa e Móvel.");
  private final JButton refreshSeriesBtn = new JButton("Atualizar Séries");
  private final JButton runBtn = new JButton("Alinhar Volumes");
  private final JButton cancelBtn = new JButton("Cancelar");

  private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

  public RegistrationTool(VolumeRegistrationService registrationService) {
    super(BUTTON_NAME, Insertable.Type.TOOL, 120);
    this.registrationService = registrationService;
    setDockableWidth(350);
    initGui();
  }

  private void initGui() {
    setLayout(new MigLayout("fillx, ins 8, wrap 2", "[right][grow,fill]", "[]6[]"));

    DefaultListCellRenderer seriesRenderer =
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DicomSeries series) {
              String modality = TagD.getTagValue(series, Tag.Modality, String.class);
              String desc = TagD.getTagValue(series, Tag.SeriesDescription, String.class);
              Integer num = TagD.getTagValue(series, Tag.SeriesNumber, Integer.class);
              setText(
                  String.format(
                      "[%s #%s] %s (%d fatias)",
                      modality != null ? modality : "OT",
                      num != null ? num : "?",
                      desc != null ? desc : "Sem descrição",
                      series.size(null)));
            }
            return this;
          }
        };
    fixedCombo.setRenderer(seriesRenderer);
    movingCombo.setRenderer(seriesRenderer);

    add(new JLabel("Volume Fixo:"));
    add(fixedCombo);

    add(new JLabel("Volume Móvel:"));
    add(movingCombo);

    add(refreshSeriesBtn, "span 2, growx");

    add(new JLabel("Bins (Mattes MI):"));
    add(binsSpinner);

    add(new JLabel("Amostragem (%):"));
    add(samplingSpinner);

    add(new JLabel("Máx. Iterações:"));
    add(maxIterSpinner);

    add(geometryInitCheck, "span 2, left");

    progressBar.setStringPainted(true);
    add(progressBar, "span 2, growx, gaptop 6");
    add(statusLabel, "span 2, growx");

    cancelBtn.setEnabled(false);
    add(runBtn, "split 2, span 2, growx");
    add(cancelBtn, "growx");

    refreshSeriesBtn.addActionListener(e -> reloadAvailableSeries());
    runBtn.addActionListener(e -> startRegistrationWorker());
    cancelBtn.addActionListener(e -> cancelFlag.set(true));
  }

  public void reloadAvailableSeries() {
    var view = EventManager.getInstance().getSelectedViewPane();
    var base = view == null ? null : FusionController.resolveSourceSeries(view);
    var overlays = new DefaultComboBoxModel<DicomSeries>();
    var bases = new DefaultComboBoxModel<DicomSeries>();
    if (base instanceof DicomSeries selected) {
      bases.addElement(selected);
      for (var candidate : FusionController.registrationCandidates(view)) {
        if (candidate instanceof DicomSeries dicomSeries) {
          overlays.addElement(dicomSeries);
        }
      }
    }
    fixedCombo.setModel(bases);
    movingCombo.setModel(overlays);
    statusLabel.setText(overlays.getSize() + " registration candidates.");
  }

  private void startRegistrationWorker() {
    DicomSeries fixed = (DicomSeries) fixedCombo.getSelectedItem();
    DicomSeries moving = (DicomSeries) movingCombo.getSelectedItem();
    if (!FusionCompatibility.isRegistrationCandidate(fixed, moving)) {
      JOptionPane.showMessageDialog(
          this,
          "Selecione duas séries volumétricas distintas (Fixa e Móvel).",
          "Registro 3D",
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    RegistrationParameters params =
        new RegistrationParameters(
            (Integer) binsSpinner.getValue(),
            ((Integer) samplingSpinner.getValue()) / 100.0,
            2.0,
            1.0e-4,
            (Integer) maxIterSpinner.getValue(),
            0.5,
            new long[] {4L, 2L, 1L},
            new double[] {2.0, 1.0, 0.0},
            geometryInitCheck.isSelected());

    cancelFlag.set(false);
    runBtn.setEnabled(false);
    cancelBtn.setEnabled(true);
    progressBar.setValue(0);
    statusLabel.setText("Calculando registro rígido 3D (Mattes MI)...");

    SwingWorker<RegistrationResult, Integer> worker =
        new SwingWorker<>() {
          @Override
          protected RegistrationResult doInBackground() {
            return registrationService.registerRigid3D(
                fixed,
                moving,
                params,
                pct -> publish((int) Math.round(pct * 100.0)),
                cancelFlag::get);
          }

          @Override
          protected void process(List<Integer> chunks) {
            int latest = chunks.get(chunks.size() - 1);
            progressBar.setValue(latest);
          }

          @Override
          protected void done() {
            runBtn.setEnabled(true);
            cancelBtn.setEnabled(false);
            try {
              RegistrationResult result = get();
              if (cancelFlag.get()) {
                statusLabel.setText("Registration canceled.");
                return;
              }
              FusionController.applyRegisteredFusion(fixed, moving, result.fixedToMovingLps4x4());
              progressBar.setValue(100);
              statusLabel.setText(
                  String.format(
                      "Concluído: MI = %.4f (%d iterações)",
                      result.finalMetricValue(), result.iterations()));
            } catch (Exception ex) {
              LOGGER.error("Erro durante o registro 3D", ex);
              statusLabel.setText("Falha/Cancelado: " + ex.getMessage());
            }
          }
        };
    worker.execute();
  }

  @Override
  public Component getToolComponent() {
    return this;
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // Mantém âncora padrão do Docking Frames no painel direito
  }
}
