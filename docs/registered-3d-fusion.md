# Registered 3D fusion

The registration bundle computes a rigid transform between the DICOM series displayed as the fixed volume and a moving volume from the same patient.
The tool lists candidates independently of the Image Fusion menu, which requires a cached registration, a shared Frame of Reference UID, or overlapping geometry within the same study for compatibility with existing PET/CT workflows.
Patient identity uses the Weasis patient key and checks the DICOM patient identifier and issuer.

The initial backend uses SimpleITK 2.4.0 with Mattes mutual information and a multiresolution pyramid.
It builds the input volumes from physically ordered DICOM slices and rejects unsupported multiframe data, irregular spacing, inconsistent orientation, and tilted stacks.
Registration runs on a background worker and reports progress and cancellation to Swing.

The result is a row-major homogeneous 4 × 4 matrix mapping fixed physical coordinates to moving physical coordinates in DICOM LPS millimeters.
The fusion sampler transforms the fixed sampling plane with this matrix before interpolating the moving volume.
The controller validates the matrix, associates it with the ordered pair of series, and activates fusion only on panes backed by the fixed series.
Opening MPR from a fused 2D view carries the active fusion state into the derived views.

See the [registration module](../weasis-dicom/weasis-dicom-registration/README.md) for the Windows x64 build and development launcher setup.

## Side-by-side registered reconstruction

In a 2D Stack layout, place the fixed and moving series in separate viewports in the same viewer and register the pair with the fixed viewport selected.
When both series are open, the moving viewport switches to a session-local reconstruction built on the fixed series' pixel grid and slice planes.
The slices are generated on demand by trilinear interpolation of the moving volume in patient space.
Stack scrolling follows the shared plane positions in either direction.
The original series stays available in the explorer, and the reconstructed view is transient.
When only the fixed series is open, registration activates the fused overlay.
