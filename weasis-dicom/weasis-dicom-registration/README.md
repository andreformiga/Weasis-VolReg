# DICOM volume registration

This optional OSGi bundle adds a rigid registration tool to the DICOM 2D and MPR viewers.
The native package configuration targets Windows x64 and uses the official [SimpleITK 2.4.0 Java distribution](https://github.com/SimpleITK/SimpleITK/releases/tag/v2.4.0).

With JDK 25 or newer, Maven 3.8.1 or newer, and the extracted Windows x64 package, run the following from the repository root in PowerShell.

```powershell
$itk = 'C:/path/to/SimpleITK-2.4.0-Java-win64'
mvn -N install:install-file "-Dfile=$itk/simpleitk-2.4.0.jar" "-DgroupId=org.itk.simple" "-DartifactId=simpleitk" "-Dversion=2.4.0" "-Dpackaging=jar"
mvn -Pvolume-registration "-Dsimpleitk.native=$itk/SimpleITKJava.dll" -pl weasis-dicom/weasis-dicom-registration -am install
```

Use `conf/registration-plugin.json` as the `felix.extended.config.properties` URI with the development launcher's base configuration.
The extension references the installed bundle through the configured Maven repository and app version. Set the launcher JVM property `app.version` to the reactor version, for example `4.8.1-SNAPSHOT`.
Build the full reactor with the same profile and native property to populate the other bundles used by the development launcher.
Enable native access for unnamed modules in the launcher JVM.

Open the fixed series in 2D or MPR.
Use the registration tool to refresh candidates and select the moving series, then start registration.
The tool applies a completed registration to the matching fixed view and provides cancellation while the worker runs.

When the fixed frame is the Registered RCS, a DICOM REG object uses the identity matrix for the fixed images and the moving-to-fixed matrix for the moving images.
The draft exporter remains disconnected from the tool while the image and cross-study instance references and required IOD attributes are completed and validated.
See [DICOM PS3.3 A.39.1](https://dicom.nema.org/medical/dicom/current/output/chtml/part03/sect_A.39.html) and [C.20.2](https://dicom.nema.org/medical/dicom/current/output/chtml/part03/sect_C.20.2.html).

## Verification

The Windows x64 reactor built with JDK 25 and Maven 3.9.11, and Spotless and all 45 viewer tests passed in the focused run.
The packaged plugin loaded its JNI library in Felix and registered two synthetic CT volumes from separate studies.
The oblique, anisotropic DICOM fixture recovered a known fixed-to-moving translation of (6, −4, 3) mm to within 0.1 mm per component in the local smoke run.
The matrix conversion matched SimpleITK point mapping for identity, translation, and centered rotation.
The service honored cancellation before startup and during native optimization, and rejected a series without enough slices.
The registered overlay was checked visually in 2D and in the three MPR views after opening MPR from the 2D menu.
