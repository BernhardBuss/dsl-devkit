/*******************************************************************************
 * Copyright (c) 2016 Avaloq Evolution AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Avaloq Evolution AG - initial API and implementation
 *******************************************************************************/
package com.avaloq.tools.ddk.check.ui.builder.util;

import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.pde.core.plugin.IPluginElement;
import org.eclipse.pde.core.plugin.IPluginExtension;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.xtext.resource.IEObjectDescription;

import com.avaloq.tools.ddk.check.check.CheckCatalog;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;


/**
 * The extension point utility class for Eclipse help contribution. Intended to be used by the Check builder participant.
 */
public class CheckTocExtensionHelper extends AbstractCheckExtensionHelper implements ICheckExtensionHelper {

  public static final String TOC_FILE_NAME = "docs/toc.xml";
  public static final String TOC_EXTENSION_POINT_ID = "org.eclipse.help.toc";

  private static final String FILE_ATTRIBUTE_TAG = "file";
  private static final String PRIMARY_ATTRIBUTE_TAG = "primary";
  private static final String TOC_ELEMENT_TAG = "toc";

  /** {@inheritDoc} */
  public Iterable<IPluginElement> getElements(final CheckCatalog catalog, final IPluginExtension extension) throws CoreException {
    List<IPluginElement> result = Lists.newArrayList();
    result.add(updateTocElement(extension.getModel().getFactory().createElement(extension)));
    return result;
  }

  /** {@inheritDoc} */
  public String getExtensionPointId() {
    return TOC_EXTENSION_POINT_ID;
  }

  /** {@inheritDoc} */
  public String getExtensionPointName(final CheckCatalog catalog) {
    return "Help extension for Check";
  }

  @Override
  protected void doUpdateExtension(final CheckCatalog catalog, final IPluginExtension extension, final Iterable<IPluginElement> elements) throws CoreException {
    if (Iterables.size(elements) == 1) {
      updateTocElement(Iterables.get(elements, 0));
    }
  }

  /**
   * Update TOC element.
   * 
   * @param element
   *          the plugin element
   * @return the the updated plugin element
   * @throws CoreException
   *           the core exception
   */
  private IPluginElement updateTocElement(final IPluginElement element) throws CoreException {
    element.setName(TOC_ELEMENT_TAG);
    element.setAttribute(FILE_ATTRIBUTE_TAG, TOC_FILE_NAME);
    element.setAttribute(PRIMARY_ATTRIBUTE_TAG, "false");
    return element;
  }

  @Override
  public boolean isExtensionUpdateRequired(final CheckCatalog catalog, final IPluginExtension extension, final Iterable<IPluginElement> elements) {
    if (extension.getPoint().equals(TOC_EXTENSION_POINT_ID) && extension.getName().equals(getExtensionPointName(catalog))) {
      for (IPluginElement element : elements) {
        if (element.getAttribute(FILE_ATTRIBUTE_TAG).getValue().equals(TOC_FILE_NAME)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public void removeExtensionFromPluginBase(final IPluginModelBase base, final IPluginExtension extension, final CheckCatalog catalog, final ExtensionType type) throws CoreException {
    // do nothing, the extension must not be removed
  }

  @Override
  public void removeExtensionFromPluginBase(final IPluginModelBase base, final IPluginExtension extension, final IEObjectDescription obj, final ExtensionType type) throws CoreException {
    // do nothing, the extension must not be removed
  }
}

