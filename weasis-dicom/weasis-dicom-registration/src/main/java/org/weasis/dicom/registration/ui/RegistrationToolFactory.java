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

import java.util.Hashtable;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.Insertable.Type;
import org.weasis.core.api.gui.InsertableFactory;
import org.weasis.dicom.registration.api.VolumeRegistrationService;

/**
 * Fábrica OSGi Declarative Services que registra o painel {@link RegistrationTool} automaticamente
 * no container {@code View2dContainer} (2D / Fusão / MPR) do Weasis.
 *
 * <p>Segue o padrão de fábrica documentado em:
 * https://weasis.org/en/basics/customize/build-plugins/
 */
@Component(
    service = InsertableFactory.class,
    property = {
      "org.weasis.dicom.viewer2d.View2dContainer=true",
      "org.weasis.dicom.viewer2d.mpr.MprContainer=true"
    })
public class RegistrationToolFactory implements InsertableFactory {

  @Reference private VolumeRegistrationService registrationService;

  private RegistrationTool toolInstance;

  @Override
  public Type getType() {
    return Type.TOOL;
  }

  @Override
  public Insertable createInstance(Hashtable<String, Object> properties) {
    if (toolInstance == null) {
      toolInstance = new RegistrationTool(registrationService);
    }
    return toolInstance;
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof RegistrationTool;
  }

  @Override
  public void dispose(Insertable component) {
    if (toolInstance != null) {
      toolInstance = null;
    }
  }

  @Deactivate
  protected void deactivate(ComponentContext context) {
    dispose(toolInstance);
  }
}
