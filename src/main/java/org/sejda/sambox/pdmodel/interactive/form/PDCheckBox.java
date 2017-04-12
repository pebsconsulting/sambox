/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sejda.sambox.pdmodel.interactive.form;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.sejda.sambox.cos.COSDictionary;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.sejda.sambox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.sejda.sambox.pdmodel.interactive.annotation.PDAppearanceEntry;

/**
 * A check box toggles between two states, on and off.
 *
 * @author Ben Litchfield
 * @author sug
 */
public final class PDCheckBox extends PDButton
{
    /**
     * @see PDField#PDField(PDAcroForm)
     *
     * @param acroForm The acroform.
     */
    public PDCheckBox(PDAcroForm acroForm)
    {
        super(acroForm);
    }

    /**
     * Constructor.
     * 
     * @param acroForm The form that this field is part of.
     * @param field the PDF object to represent as a field.
     * @param parent the parent node of the node
     */
    PDCheckBox(PDAcroForm acroForm, COSDictionary field, PDNonTerminalField parent)
    {
        super(acroForm, field, parent);
    }

    /**
     * This will tell if this radio button is currently checked or not. This is equivalent to calling
     * {@link #getValue()}.
     *
     * @return true If this field is checked.
     */
    public boolean isChecked()
    {
        if(COSName.Off.getName().equals(getValue()))
        {
            return false;
        }

        return getOnValues().contains(getValue()) ||
                // export values exist but they don't match normal appearances
                getNormalAppearanceValues().contains(getValue()) ||
                // no appearances
                COSName.YES.getName().equals(getValue());
    }

    /**
     * Checks the check box.
     * 
     * @throws IOException if the appearance couldn't be generated.
     */
    public void check() throws IOException
    {
        setValue(getOnValue());
    }

    /**
     * Unchecks the check box.
     * 
     * @throws IOException if the appearance couldn't be generated.
     */
    public void unCheck() throws IOException
    {
        setValue(COSName.Off.getName());
    }

    /**
     * Get the value which sets the check box to the On state.
     * 
     * <p>
     * The On value should be 'Yes' but other values are possible so we need to look for that. On the other hand the Off
     * value shall always be 'Off'. If not set or not part of the normal appearance keys 'Off' is the default
     * </p>
     *
     * @return the value setting the check box to the On state
     * definition.
     */
    public String getOnValue()
    {
        List<String> exportValues = getExportValues();
        if(exportValues.size() > 0) {
            return exportValues.get(0);
        }

        PDAnnotationWidget widget = this.getWidgets().get(0);
        PDAppearanceDictionary apDictionary = widget.getAppearance();

        String onValue = "Yes";
        if (apDictionary != null)
        {
            PDAppearanceEntry normalAppearance = apDictionary.getNormalAppearance();
            if (normalAppearance != null)
            {
                Set<COSName> entries = normalAppearance.getSubDictionary().keySet();
                for (COSName entry : entries)
                {
                    if (COSName.Off.compareTo(entry) != 0)
                    {
                        onValue = entry.getName();
                    }
                }
            }
        }
        return onValue;
    }

}
