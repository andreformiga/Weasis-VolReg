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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.image.OpManager;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.HiddenSeriesManager;
import org.weasis.dicom.codec.HiddenSpecialElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.viewer2d.EventManager;
import org.weasis.dicom.viewer2d.View2d;
import org.weasis.dicom.viewer2d.View2dContainer;
import org.weasis.dicom.viewer2d.mpr.MprView;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.opencv.op.lut.ByteLut;
import org.weasis.opencv.op.lut.colormap.ColorMap;

/**
 * Stateless helpers shared by the fusion EventManager actions: they apply a parameter to every pane
 * of the selected container (so MPR planes stay in sync), build the resampled PET volume off the
 * EDT, and list the study series compatible with the displayed base series.
 */
public final class FusionController {
  private static final Logger LOGGER = LoggerFactory.getLogger(FusionController.class);

  private record SeriesPairKey(String baseSeriesUid, String overlaySeriesUid) {}

  private static final Set<MediaSeries<DicomImageElement>> buildingVolumes =
      ConcurrentHashMap.newKeySet();

  private static final ConcurrentHashMap<SeriesPairKey, double[]> registeredTransforms =
      new ConcurrentHashMap<>();

  private FusionController() {}

  public static boolean hasRegisteredTransform(
      MediaSeries<DicomImageElement> base, MediaSeries<DicomImageElement> overlay) {
    return getRegisteredTransform(base, overlay) != null;
  }

  public static double[] getRegisteredTransform(
      MediaSeries<DicomImageElement> base, MediaSeries<DicomImageElement> overlay) {
    if (base == null || overlay == null) {
      return null;
    }
    String baseUid = TagD.getTagValue(base, Tag.SeriesInstanceUID, String.class);
    String overlayUid = TagD.getTagValue(overlay, Tag.SeriesInstanceUID, String.class);
    if (baseUid == null || overlayUid == null) {
      return null;
    }
    double[] m = registeredTransforms.get(new SeriesPairKey(baseUid, overlayUid));
    return m != null ? m.clone() : null;
  }

  /**
   * Registers a 4x4 row-major LPS matrix (Fixed -> Moving) for {@code (baseSeries, overlaySeries)}
   * and activates 3D resliced fusion on the active 2D view or across all 3 MPR planes.
   */
  public static void applyRegisteredFusion(
      MediaSeries<DicomImageElement> baseSeries,
      MediaSeries<DicomImageElement> overlaySeries,
      double[] fixedToMovingLps4x4) {
    Objects.requireNonNull(fixedToMovingLps4x4);
    double[] matrix = fixedToMovingLps4x4.clone();
    validateTransform(matrix);
    if (!FusionCompatibility.isRegistrationCandidate(baseSeries, overlaySeries)) {
      throw new IllegalArgumentException("Incompatible registration series");
    }
    String baseUid = TagD.getTagValue(baseSeries, Tag.SeriesInstanceUID, String.class);
    String overlayUid = TagD.getTagValue(overlaySeries, Tag.SeriesInstanceUID, String.class);
    if (baseUid == null
        || baseUid.isBlank()
        || overlayUid == null
        || overlayUid.isBlank()
        || baseUid.equals(overlayUid)) {
      throw new IllegalArgumentException("Registration requires two distinct series UIDs");
    }
    registeredTransforms.put(new SeriesPairKey(baseUid, overlayUid), matrix);
    GuiExecutor.execute(() -> activateRegisteredFusion(baseSeries, overlaySeries, matrix));
  }

  static void validateTransform(double[] matrix) {
    if (matrix.length != 16
        || java.util.Arrays.stream(matrix).anyMatch(v -> !Double.isFinite(v))
        || matrix[12] != 0
        || matrix[13] != 0
        || matrix[14] != 0
        || matrix[15] != 1) {
      throw new IllegalArgumentException("Expected a finite homogeneous LPS matrix");
    }
    double determinant =
        matrix[0] * (matrix[5] * matrix[10] - matrix[6] * matrix[9])
            - matrix[1] * (matrix[4] * matrix[10] - matrix[6] * matrix[8])
            + matrix[2] * (matrix[4] * matrix[9] - matrix[5] * matrix[8]);
    if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-12) {
      throw new IllegalArgumentException("Registration matrix must be invertible");
    }
  }

  private static void activateRegisteredFusion(
      MediaSeries<DicomImageElement> baseSeries,
      MediaSeries<DicomImageElement> overlaySeries,
      double[] matrix) {
    List<ViewCanvas<DicomImageElement>> views =
        targetViews().stream().filter(view -> resolveSourceSeries(view) == baseSeries).toList();
    if (views.isEmpty()) {
      return;
    }
    var manager = EventManager.getInstance();
    if (manager.getSelectedView2dContainer() instanceof View2dContainer container
        && views.getFirst() instanceof View2d fixedView) {
      var movingView =
          container.getViewportPanes().stream()
              .filter(viewport -> !viewport.isTiled())
              .map(viewport -> viewport.getSelectedCanvas())
              .filter(View2d.class::isInstance)
              .filter(
                  view ->
                      view.getSeries() == overlaySeries
                          || (view.getSeries() instanceof RegisteredResliceSeries existing
                              && existing.fixed() == baseSeries
                              && existing.moving() == overlaySeries))
              .findFirst()
              .orElse(null);
      if (movingView != null) {
        reconstructComparison(container, fixedView, movingView, baseSeries, overlaySeries, matrix);
        return;
      }
    }
    FusionWindow provisional = provisionalWindow(overlaySeries);
    for (ViewCanvas<DicomImageElement> view : views) {
      OpManager disOp = view.getDisplayOpManager();
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_SERIES, overlaySeries);
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME, null);
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_WINDOW, provisional);
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_TRANSFORM, matrix.clone());
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_ENABLED, Boolean.TRUE);
      clearCache(disOp);
      view.getImageLayer().updateDisplayOperations();
    }
    EventManager.getInstance().refreshFusionWindowControl();
    buildVolume(overlaySeries);
  }

  private static void reconstructComparison(
      View2dContainer container,
      View2d fixedView,
      ViewCanvas<DicomImageElement> movingView,
      MediaSeries<DicomImageElement> fixed,
      MediaSeries<DicomImageElement> moving,
      double[] matrix) {
    var previousSeries = movingView.getSeries();
    java.util.concurrent.CompletableFuture.supplyAsync(
            () -> RegisteredResliceSeries.build(fixed, moving, matrix))
        .whenComplete(
            (reconstruction, failure) ->
                GuiExecutor.execute(
                    () -> {
                      var manager = EventManager.getInstance();
                      if (failure != null || reconstruction == null) {
                        LOGGER.warn("Cannot reconstruct the moving series", failure);
                        return;
                      }
                      if (manager.getSelectedView2dContainer() != container
                          || !container.getView2ds().containsAll(List.of(fixedView, movingView))
                          || fixedView.getSeries() != fixed
                          || movingView.getSeries() != previousSeries
                          || !java.util.Arrays.equals(
                              matrix, getRegisteredTransform(fixed, moving))) {
                        return;
                      }
                      var fixedImage = fixedView.getImage();
                      var location =
                          fixedImage == null ? null : fixedImage.getTagValue(TagW.SlicePosition);
                      var first =
                          location instanceof Double pos
                              ? reconstruction.getNearestImage(pos, 0, null, null)
                              : null;
                      var display = fixedView.getDisplayOpManager();
                      display.setParamValue(
                          FusionOp.OP_NAME, FusionOp.P_FUSION_ENABLED, Boolean.FALSE);
                      fixedView.getImageLayer().updateDisplayOperations();
                      movingView.setSeries(reconstruction, first);
                      manager.updateComponentsListener(container.getSelectedViewCanvas());
                    }));
  }

  /** {@code true} while the rectified volume of the given overlay series is being built. */
  public static boolean isVolumeBuilding(Object series) {
    return series instanceof MediaSeries<?> ms && buildingVolumes.contains(ms);
  }

  /** Every pane of the selected container, whatever it displays. */
  private static List<ViewCanvas<DicomImageElement>> containerViews() {
    ImageViewerPlugin<DicomImageElement> container =
        EventManager.getInstance().getSelectedView2dContainer();
    return container == null ? List.of() : container.getView2ds();
  }

  /**
   * The panes a fusion change applies to: the selected one. Fusion is per-view, so the other panes
   * of a layout keep whatever they show – the same series left unfused for comparison as much as an
   * unrelated one, or the functional series itself.
   *
   * <p>MPR is the exception: its panes are three planes of a single acquisition and only ever show
   * that one volume, so they take the change together and stay in sync.
   */
  private static List<ViewCanvas<DicomImageElement>> targetViews() {
    ViewCanvas<DicomImageElement> selected = EventManager.getInstance().getSelectedViewPane();
    if (selected == null) {
      return List.of();
    }
    return selected instanceof MprView ? containerViews() : List.of(selected);
  }

  /**
   * Captures the active fusion configuration of {@code view}, or {@code null} when fusion is off or
   * has no overlay series. Used to seed a newly opened MPR with the 2D view's fusion.
   */
  public static FusionState snapshot(ViewCanvas<DicomImageElement> view) {
    if (view == null) {
      return null;
    }
    OpManager disOp = view.getDisplayOpManager();
    boolean enabled =
        disOp
            .getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_ENABLED, Boolean.class)
            .orElse(Boolean.FALSE);
    if (!enabled
        || !(disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_SERIES).orElse(null)
            instanceof MediaSeries<?> series)) {
      return null;
    }
    ByteLut lut =
        disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_LUT).orElse(null)
                instanceof ByteLut l
            ? l
            : null;
    FusionWindow window =
        disOp
            .getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_WINDOW, FusionWindow.class)
            .orElse(null);
    double base =
        disOp
            .getParamValue(FusionOp.OP_NAME, FusionOp.P_OPACITY_BASE, Double.class)
            .orElse(FusionOp.DEFAULT_BASE_OPACITY);
    double overlay =
        disOp
            .getParamValue(FusionOp.OP_NAME, FusionOp.P_OPACITY_OVERLAY, Double.class)
            .orElse(FusionOp.DEFAULT_OVERLAY_OPACITY);
    Volume<?, ?> volume =
        disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME).orElse(null)
                instanceof Volume<?, ?> v
            ? v
            : null;
    double[] lpsTransform =
        disOp
            .getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_TRANSFORM, double[].class)
            .orElse(null);
    @SuppressWarnings("unchecked")
    MediaSeries<DicomImageElement> overlaySeries = (MediaSeries<DicomImageElement>) series;
    return new FusionState(overlaySeries, lut, window, base, overlay, volume, lpsTransform);
  }

  /**
   * Applies an inherited {@link FusionState} to the given panes (typically the MPR planes). Reuses
   * the snapshot's volume when present, otherwise rebuilds it for the target geometry.
   */
  public static void applyState(List<ViewCanvas<DicomImageElement>> views, FusionState state) {
    if (state == null || views == null || views.isEmpty()) {
      return;
    }
    for (ViewCanvas<DicomImageElement> view : views) {
      OpManager disOp = view.getDisplayOpManager();
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_SERIES, state.series());
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_LUT, state.lut());
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_WINDOW, state.window());
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_OPACITY_BASE, state.baseOpacity());
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_OPACITY_OVERLAY, state.overlayOpacity());
      if (state.volume() != null) {
        disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME, state.volume());
      }
      disOp.setParamValue(
          FusionOp.OP_NAME, FusionOp.P_FUSION_TRANSFORM, state.fixedToMovingLps4x4());
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_ENABLED, Boolean.TRUE);
      clearCache(disOp);
      view.getImageLayer().updateDisplayOperations();
    }
    EventManager.getInstance().refreshFusionWindowControl();
    if (state.volume() == null) {
      buildVolume(state.series());
    }
  }

  /**
   * Selects the overlay color map. A map anchored to physical values also declares the display
   * window (an SUVbw preset sets an SUVbw window), so it replaces the current one.
   */
  public static void applyLut(Object lut) {
    applyParam(FusionOp.P_FUSION_LUT, lut);
    ColorMap map = lut instanceof ByteLut byteLut ? byteLut.source() : null;
    ViewCanvas<DicomImageElement> view = targetViews().isEmpty() ? null : targetViews().getFirst();
    if (map == null || view == null) {
      return;
    }
    OpManager disOp = view.getDisplayOpManager();
    @SuppressWarnings("unchecked")
    MediaSeries<DicomImageElement> series =
        disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_SERIES).orElse(null)
                instanceof MediaSeries<?> ms
            ? (MediaSeries<DicomImageElement>) ms
            : null;
    Volume<?, ?> volume =
        disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME).orElse(null)
                instanceof Volume<?, ?> v
            ? v
            : null;
    FusionWindow declared = FusionWindow.declaredBy(map, series, volume);
    if (declared != null) {
      applyParam(FusionOp.P_FUSION_WINDOW, declared);
    }
  }

  /** The map behind the overlay LUT of the first target pane, or null. */
  private static ColorMap currentMap() {
    if (targetViews().isEmpty()) {
      return null;
    }
    return targetViews()
                .getFirst()
                .getDisplayOpManager()
                .getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_LUT)
                .orElse(null)
            instanceof ByteLut lut
        ? lut.source()
        : null;
  }

  /** Applies a FusionOp parameter to every pane of the current container. */
  public static void applyParam(String propertyName, Object value) {
    setParam(propertyName, value);
    if (FusionOp.P_FUSION_WINDOW.equals(propertyName)) {
      EventManager.getInstance().refreshFusionWindowControl();
    }
  }

  /**
   * Moves the top of the display window to {@code displayMax} (in its display unit), the bottom
   * unchanged. Leaves the window control alone: it is the caller.
   */
  public static void applyWindowTop(double displayMax) {
    FusionWindow window = currentWindow();
    if (window != null && Double.isFinite(displayMax) && displayMax > window.displayMin()) {
      setParam(FusionOp.OP_NAME, window.withDisplayMax(displayMax));
    }
  }

  /** The display window of the selected pane, or {@code null} when it has none. */
  public static FusionWindow currentWindow() {
    List<ViewCanvas<DicomImageElement>> views = targetViews();
    return views.isEmpty() ? null : windowOf(views.getFirst());
  }

  /** The display window of {@code view}, or {@code null} when it has none. */
  public static FusionWindow windowOf(ViewCanvas<DicomImageElement> view) {
    if (view == null) {
      return null;
    }
    return view.getDisplayOpManager()
        .getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_WINDOW, FusionWindow.class)
        .orElse(null);
  }

  /**
   * Highest top the window of {@code view} can be given, in its display unit (see {@link
   * FusionWindow#displayTopLimit}), or {@code NaN} when it has no window.
   */
  @SuppressWarnings("unchecked")
  public static double windowTopLimit(ViewCanvas<DicomImageElement> view) {
    FusionWindow window = windowOf(view);
    if (window == null) {
      return Double.NaN;
    }
    OpManager disOp = view.getDisplayOpManager();
    MediaSeries<DicomImageElement> series =
        disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_SERIES).orElse(null)
                instanceof MediaSeries<?> ms
            ? (MediaSeries<DicomImageElement>) ms
            : null;
    Volume<?, ?> volume =
        disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME).orElse(null)
                instanceof Volume<?, ?> v
            ? v
            : null;
    return window.displayTopLimit(FusionWindow.dataMax(series, volume));
  }

  private static void setParam(String propertyName, Object value) {
    // The cached overlays bake in the color LUT, the selected series and the resampled volume, so
    // they must be invalidated when any of those changes. Opacity is applied at composite time and
    // does not need a cache clear.
    boolean seriesChange = FusionOp.P_FUSION_SERIES.equals(propertyName);
    boolean clearCache =
        seriesChange
            || FusionOp.P_FUSION_LUT.equals(propertyName)
            || FusionOp.P_FUSION_WINDOW.equals(propertyName)
            || FusionOp.P_FUSION_VOLUME.equals(propertyName)
            || FusionOp.P_FUSION_TRANSFORM.equals(propertyName);
    for (ViewCanvas<DicomImageElement> view : targetViews()) {
      OpManager disOp = view.getDisplayOpManager();
      disOp.setParamValue(FusionOp.OP_NAME, propertyName, value);
      // A new overlay series invalidates the volume built for the previous one; drop it so fusion
      // falls back to the single-slice path until buildVolume() supplies the matching volume,
      // instead of reslicing the previous series' voxels. Its window is just as stale: dropping it
      // makes the op fall back to the new series' default until applyDefaultWindow() pushes one.
      if (seriesChange) {
        disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME, null);
        disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_WINDOW, null);
        @SuppressWarnings("unchecked")
        MediaSeries<DicomImageElement> newOverlay =
            value instanceof MediaSeries<?> ms ? (MediaSeries<DicomImageElement>) ms : null;
        disOp.setParamValue(
            FusionOp.OP_NAME,
            FusionOp.P_FUSION_TRANSFORM,
            getRegisteredTransform(resolveSourceSeries(view), newOverlay));
      }
      if (clearCache) {
        clearCache(disOp);
      }
      view.getImageLayer().updateDisplayOperations();
    }
  }

  /**
   * The provisional window of an overlay series, measured on a single slice, or {@code null} when
   * it holds no image. Refined by {@link #buildVolume} once the whole series can be measured.
   */
  @SuppressWarnings("unchecked")
  public static FusionWindow provisionalWindow(Object series) {
    return series instanceof MediaSeries<?> ms
        ? FusionWindow.fromSlice((MediaSeries<DicomImageElement>) ms, currentMap())
        : null;
  }

  private static void clearCache(OpManager disOp) {
    disOp
        .getNode(FusionOp.OP_NAME)
        .ifPresent(
            node -> {
              if (node instanceof FusionOp fusionOp) {
                fusionOp.clearCache();
              }
            });
  }

  /**
   * Builds the rectified PET volume off the EDT so fusion can be resliced on any plane, then
   * applies it to every pane. Until the build completes, fusion falls back to the single-slice
   * path.
   */
  public static void buildVolume(Object selectedSeries) {
    if (!(selectedSeries instanceof MediaSeries)) {
      return;
    }
    @SuppressWarnings("unchecked")
    MediaSeries<DicomImageElement> overlaySeries = (MediaSeries<DicomImageElement>) selectedSeries;
    buildingVolumes.add(overlaySeries);
    ColorMap map = currentMap();
    // The window shown while the build runs; one the reader changed meanwhile is kept.
    FusionWindow shown = currentWindow();
    GuiExecutor.execute(FusionController::repaintViews);
    Thread worker =
        new Thread(
            () -> {
              try {
                Volume<?, ?> volume = FusionVolumeBuilder.build(overlaySeries);
                if (volume != null) {
                  // Measured here rather than in applyVolume: it scans the voxels.
                  FusionWindow window = FusionWindow.fromVolume(overlaySeries, volume, map);
                  GuiExecutor.execute(() -> applyVolume(overlaySeries, volume, window, shown));
                }
              } finally {
                buildingVolumes.remove(overlaySeries);
                // The loading message is evaluated at paint time: repaint whatever the outcome so
                // it also disappears when the volume could not be built.
                GuiExecutor.execute(FusionController::repaintViews);
              }
            },
            "fusion-volume-builder"); // NON-NLS
    worker.setDaemon(true);
    worker.start();
  }

  /** Repaints every pane of the selected container. */
  private static void repaintViews() {
    for (ViewCanvas<DicomImageElement> view : containerViews()) {
      view.getJComponent().repaint();
    }
  }

  /**
   * Applies an asynchronously built volume, but only to panes still showing {@code series}. The
   * user may have switched the overlay series (or turned fusion off) while the build ran, and two
   * builds can finish out of order; applying unconditionally would overlay the previous series'
   * voxels.
   *
   * <p>Scans the whole container rather than the current fusion targets: the selection can have
   * moved while the build ran, and carrying {@code series} as the overlay is the condition that
   * matters here.
   *
   * <p>The refined window only replaces {@code shown}, the provisional one the build started with:
   * a window the reader set in the meantime (moved top, other color map) is theirs to keep.
   */
  private static void applyVolume(
      MediaSeries<DicomImageElement> series,
      Volume<?, ?> volume,
      FusionWindow refined,
      FusionWindow shown) {
    for (ViewCanvas<DicomImageElement> view : containerViews()) {
      OpManager disOp = view.getDisplayOpManager();
      if (disOp.getParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_SERIES).orElse(null) != series) {
        continue;
      }
      disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_VOLUME, volume);
      if (refined != null && Objects.equals(windowOf(view), shown)) {
        disOp.setParamValue(FusionOp.OP_NAME, FusionOp.P_FUSION_WINDOW, refined);
      }
      clearCache(disOp);
      view.getImageLayer().updateDisplayOperations();
    }
    EventManager.getInstance().refreshFusionWindowControl();
  }

  /**
   * Lists the series that can be fused onto the displayed base series (see {@link
   * FusionCompatibility}).
   */
  public static List<MediaSeries<DicomImageElement>> compatibleSeries(
      ViewCanvas<DicomImageElement> view) {
    return candidates(view, FusionCompatibility::isCompatible);
  }

  /** Lists volumes of the current patient that can be registered to the displayed source. */
  public static List<MediaSeries<DicomImageElement>> registrationCandidates(
      ViewCanvas<DicomImageElement> view) {
    return candidates(view, FusionCompatibility::isRegistrationCandidate);
  }

  @SuppressWarnings("unchecked")
  private static List<MediaSeries<DicomImageElement>> candidates(
      ViewCanvas<DicomImageElement> view,
      java.util.function.BiPredicate<MediaSeries<DicomImageElement>, MediaSeries<DicomImageElement>>
          acceptance) {
    List<MediaSeries<DicomImageElement>> result = new ArrayList<>();
    if (view == null || view.getSeries() == null) {
      return result;
    }
    MediaSeries<DicomImageElement> refSeries = resolveSourceSeries(view);
    if (refSeries == null
        || !(refSeries.getTagValue(TagW.ExplorerModel) instanceof DicomModel model)) {
      return result;
    }
    MediaSeriesGroup study = model.getParent(refSeries, DicomModel.study);
    if (study == null) {
      return result;
    }
    MediaSeriesGroup patient = model.getParent(study, DicomModel.patient);
    Iterable<MediaSeriesGroup> studies =
        patient != null ? model.getChildren(patient) : List.of(study);
    for (MediaSeriesGroup st : studies) {
      for (MediaSeriesGroup seriesGroup : model.getChildren(st)) {
        if (seriesGroup instanceof MediaSeries<?> ms
            && acceptance.test(refSeries, (MediaSeries<DicomImageElement>) ms)) {
          result.add((MediaSeries<DicomImageElement>) ms);
        }
      }
    }
    for (MediaSeries<DicomImageElement> hidden : hiddenImageSeries(refSeries)) {
      if (!result.contains(hidden) && acceptance.test(refSeries, hidden)) {
        result.add(hidden);
      }
    }
    return result;
  }

  /**
   * Image series carried by hidden elements referencing {@code base}, such as the dose grids of an
   * RT case; they are not children of the study node but can be overlaid all the same.
   */
  private static List<MediaSeries<DicomImageElement>> hiddenImageSeries(
      MediaSeries<DicomImageElement> base) {
    String uid = TagD.getTagValue(base, Tag.SeriesInstanceUID, String.class);
    Set<String> linked = HiddenSeriesManager.getInstance().reference2Series.get(uid);
    if (linked == null || linked.isEmpty()) {
      return List.of();
    }
    return HiddenSeriesManager.getHiddenElementsFromSeries(
            HiddenSpecialElement.class, linked.toArray(String[]::new))
        .stream()
        .map(HiddenSpecialElement::getImageSeries)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  /**
   * DICOM Modality of the displayed base series (resolving the MPR source series), or {@code null}.
   */
  public static String baseModality(ViewCanvas<DicomImageElement> view) {
    return view == null || view.getSeries() == null ? null : modalityOf(resolveSourceSeries(view));
  }

  /** DICOM Modality of a series (e.g. CT, MR, PT, NM), or {@code null}. */
  public static String modalityOf(Object series) {
    return series instanceof MediaSeries<?> ms
        ? TagD.getTagValue(ms, Tag.Modality, String.class)
        : null;
  }

  /** Original acquisition series backing the view (the MPR source series, or the view's own). */
  public static MediaSeries<DicomImageElement> resolveSourceSeries(
      ViewCanvas<DicomImageElement> view) {
    if (view instanceof MprView mprView && mprView.getMprController() != null) {
      Volume<?, ?> volume = mprView.getMprController().getVolume();
      if (volume != null && volume.getStack() != null && volume.getStack().getSeries() != null) {
        return volume.getStack().getSeries();
      }
    }
    return view.getSeries();
  }
}
