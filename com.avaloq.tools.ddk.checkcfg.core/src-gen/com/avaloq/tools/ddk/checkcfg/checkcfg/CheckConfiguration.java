/**
 */
package com.avaloq.tools.ddk.checkcfg.checkcfg;

import org.eclipse.emf.common.util.EList;

import org.eclipse.emf.ecore.EObject;

/**
 * <!-- begin-user-doc -->
 * A representation of the model object '<em><b>Check Configuration</b></em>'.
 * <!-- end-user-doc -->
 *
 * <p>
 * The following features are supported:
 * <ul>
 *   <li>{@link com.avaloq.tools.ddk.checkcfg.checkcfg.CheckConfiguration#getName <em>Name</em>}</li>
 *   <li>{@link com.avaloq.tools.ddk.checkcfg.checkcfg.CheckConfiguration#getLanguageValidatorConfigurations <em>Language Validator Configurations</em>}</li>
 *   <li>{@link com.avaloq.tools.ddk.checkcfg.checkcfg.CheckConfiguration#getLegacyCatalogConfigurations <em>Legacy Catalog Configurations</em>}</li>
 * </ul>
 * </p>
 *
 * @see com.avaloq.tools.ddk.checkcfg.checkcfg.CheckcfgPackage#getCheckConfiguration()
 * @model
 * @generated
 */
public interface CheckConfiguration extends EObject
{
	/**
	 * Returns the value of the '<em><b>Name</b></em>' attribute.
	 * <!-- begin-user-doc -->
	 * <p>
	 * If the meaning of the '<em>Name</em>' attribute isn't clear,
	 * there really should be more of a description here...
	 * </p>
	 * <!-- end-user-doc -->
	 * @return the value of the '<em>Name</em>' attribute.
	 * @see #setName(String)
	 * @see com.avaloq.tools.ddk.checkcfg.checkcfg.CheckcfgPackage#getCheckConfiguration_Name()
	 * @model
	 * @generated
	 */
	String getName();

	/**
	 * Sets the value of the '{@link com.avaloq.tools.ddk.checkcfg.checkcfg.CheckConfiguration#getName <em>Name</em>}' attribute.
	 * <!-- begin-user-doc -->
	 * <!-- end-user-doc -->
	 * @param value the new value of the '<em>Name</em>' attribute.
	 * @see #getName()
	 * @generated
	 */
	void setName(String value);

	/**
	 * Returns the value of the '<em><b>Language Validator Configurations</b></em>' containment reference list.
	 * The list contents are of type {@link com.avaloq.tools.ddk.checkcfg.checkcfg.ConfiguredLanguageValidator}.
	 * <!-- begin-user-doc -->
	 * <p>
	 * If the meaning of the '<em>Language Validator Configurations</em>' containment reference list isn't clear,
	 * there really should be more of a description here...
	 * </p>
	 * <!-- end-user-doc -->
	 * @return the value of the '<em>Language Validator Configurations</em>' containment reference list.
	 * @see com.avaloq.tools.ddk.checkcfg.checkcfg.CheckcfgPackage#getCheckConfiguration_LanguageValidatorConfigurations()
	 * @model containment="true"
	 * @generated
	 */
	EList<ConfiguredLanguageValidator> getLanguageValidatorConfigurations();

	/**
	 * Returns the value of the '<em><b>Legacy Catalog Configurations</b></em>' containment reference list.
	 * The list contents are of type {@link com.avaloq.tools.ddk.checkcfg.checkcfg.ConfiguredCatalog}.
	 * <!-- begin-user-doc -->
	 * <p>
	 * If the meaning of the '<em>Legacy Catalog Configurations</em>' containment reference list isn't clear,
	 * there really should be more of a description here...
	 * </p>
	 * <!-- end-user-doc -->
	 * @return the value of the '<em>Legacy Catalog Configurations</em>' containment reference list.
	 * @see com.avaloq.tools.ddk.checkcfg.checkcfg.CheckcfgPackage#getCheckConfiguration_LegacyCatalogConfigurations()
	 * @model containment="true"
	 * @generated
	 */
	EList<ConfiguredCatalog> getLegacyCatalogConfigurations();

} // CheckConfiguration
