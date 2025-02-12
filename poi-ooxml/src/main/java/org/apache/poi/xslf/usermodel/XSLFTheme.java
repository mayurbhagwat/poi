/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */
package org.apache.poi.xslf.usermodel;

import static org.apache.poi.ooxml.POIXMLTypeLoader.DEFAULT_XML_OPTIONS;

import java.io.IOException;
import java.io.OutputStream;

import javax.xml.namespace.QName;

import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.util.Beta;
import org.apache.poi.util.Internal;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBaseStyles;
import org.openxmlformats.schemas.drawingml.x2006.main.CTColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTColorScheme;
import org.openxmlformats.schemas.drawingml.x2006.main.CTOfficeStyleSheet;
import org.openxmlformats.schemas.drawingml.x2006.main.ThemeDocument;

/**
 * A shared style sheet in a .pptx slide show
 */
@Beta
public class XSLFTheme extends POIXMLDocumentPart {
    private CTOfficeStyleSheet _theme;

    XSLFTheme() {
        _theme = CTOfficeStyleSheet.Factory.newInstance();
    }

    /**
     * @since POI 3.14-Beta1
     */
    public XSLFTheme(PackagePart part) throws IOException, XmlException {
        super(part);
        ThemeDocument doc =
            ThemeDocument.Factory.parse(getPackagePart().getInputStream(), DEFAULT_XML_OPTIONS);
        _theme = doc.getTheme();
    }

    @SuppressWarnings("WeakerAccess")
    public void importTheme(XSLFTheme theme) {
        _theme = theme.getXmlObject();
    }

    /**
     *
     * @return name of this theme, e.g. "Office Theme"
     */
    public String getName(){
        return _theme.getName();
    }

    /**
     * Set name of this theme
     *
     * @param name name of this theme
     */
    public void setName(String name){
        _theme.setName(name);
    }

    /**
     * Get a color from the theme's color scheme by name
     *
     * @return a theme color or <code>null</code> if not found
     */
    @Internal
    public CTColor getCTColor(String name) {
        CTBaseStyles elems = _theme.getThemeElements();
        CTColorScheme scheme = (elems == null) ? null : elems.getClrScheme();
        return getMapColor(name, scheme);
    }


    private static CTColor getMapColor(String mapName, CTColorScheme scheme) {
        if (mapName == null || scheme == null) {
            return null;
        }
        switch (mapName) {
            case "accent1":
                return scheme.getAccent1();
            case "accent2":
                return scheme.getAccent2();
            case "accent3":
                return scheme.getAccent3();
            case "accent4":
                return scheme.getAccent4();
            case "accent5":
                return scheme.getAccent5();
            case "accent6":
                return scheme.getAccent6();
            case "dk1":
                return scheme.getDk1();
            case "dk2":
                return scheme.getDk2();
            case "folHlink":
                return scheme.getFolHlink();
            case "hlink":
                return scheme.getHlink();
            case "lt1":
                return scheme.getLt1();
            case "lt2":
                return scheme.getLt2();
            default:
                return null;
        }
    }

    /**
     * While developing only!
     */
    @Internal
    public CTOfficeStyleSheet getXmlObject() {
        return _theme;
    }

    @Override
    protected final void commit() throws IOException {
        XmlOptions xmlOptions = new XmlOptions(DEFAULT_XML_OPTIONS);
        xmlOptions.setSaveSyntheticDocumentElement(
            new QName(XSLFRelation.NS_DRAWINGML, "theme"));

        PackagePart part = getPackagePart();
        OutputStream out = part.getOutputStream();
        getXmlObject().save(out, xmlOptions);
        out.close();
    }

    /**
     * @return typeface of the major font to use in a document.
     * Typically the major font is used for heading areas of a document.
     *
     */
    @SuppressWarnings("WeakerAccess")
    public String getMajorFont(){
        return _theme.getThemeElements().getFontScheme().getMajorFont().getLatin().getTypeface();
    }

    /**
     * @return typeface of the minor font to use in a document.
     * Typically the monor font is used for normal text or paragraph areas.
     *
     */
    @SuppressWarnings("WeakerAccess")
    public String getMinorFont(){
        return _theme.getThemeElements().getFontScheme().getMinorFont().getLatin().getTypeface();
    }
}
