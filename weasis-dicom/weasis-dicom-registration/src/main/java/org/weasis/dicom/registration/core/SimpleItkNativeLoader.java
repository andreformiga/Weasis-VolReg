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

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inicializador idempotente da biblioteca JNI do SimpleITK dentro do container OSGi Apache Felix.
 */
public final class SimpleItkNativeLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger(SimpleItkNativeLoader.class);
  private static final String JNI_LIBRARY_NAME = "SimpleITKJava";
  private static final AtomicBoolean LOADED = new AtomicBoolean(false);

  private SimpleItkNativeLoader() {}

  public static void ensureLoaded() {
    if (LOADED.get()) {
      return;
    }
    synchronized (SimpleItkNativeLoader.class) {
      if (LOADED.get()) {
        return;
      }
      try {
        // Resolvido automaticamente pelo cabeçalho OSGi `Bundle-NativeCode` (ou
        // -Djava.library.path)
        System.loadLibrary(JNI_LIBRARY_NAME);
        LOADED.set(true);
        LOGGER.info(
            "Biblioteca nativa JNI '{}' carregada no bundle de registro.", JNI_LIBRARY_NAME);
      } catch (UnsatisfiedLinkError err) {
        throw new IllegalStateException(
            "Falha ao carregar '"
                + JNI_LIBRARY_NAME
                + "'. Empacote o binário nativo do SimpleITK em src/main/resources/native/<os-arch>/ "
                + "ou configure -Djava.library.path.",
            err);
      }
    }
  }
}
