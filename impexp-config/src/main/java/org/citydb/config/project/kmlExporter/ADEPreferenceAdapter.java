/*
 * 3D City Database - The Open Source CityGML Database
 * http://www.3dcitydb.org/
 *
 * Copyright 2013 - 2020
 * Chair of Geoinformatics
 * Technical University of Munich, Germany
 * https://www.gis.bgu.tum.de/
 *
 * The 3D City Database is jointly developed with the following
 * cooperation partners:
 *
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * M.O.S.S. Computer Grafik Systeme GmbH, Taufkirchen <http://www.moss.de/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.citydb.config.project.kmlExporter;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ADEPreferenceAdapter extends XmlAdapter<ADEPreferenceAdapter.ADEKmlExporterPreferenceList, Map<String, ADEPreference>> {

    public static class ADEKmlExporterPreferenceList {
        @XmlElement(name = "preference")
        private List<ADEPreference> preferences;
    }

    @Override
    public Map<String, ADEPreference> unmarshal(ADEKmlExporterPreferenceList v) {
        Map<String, ADEPreference> map = null;

        if (v != null && v.preferences != null && !v.preferences.isEmpty()) {
            map = new HashMap<>();
            for (ADEPreference preference : v.preferences) {
                if (preference.isSetTarget())
                    map.put(preference.getTarget(), preference);
            }
        }

        return map;
    }

    @Override
    public ADEKmlExporterPreferenceList marshal(Map<String, ADEPreference> v) {
        ADEKmlExporterPreferenceList list = null;

        if (v != null && !v.isEmpty()) {
            list = new ADEKmlExporterPreferenceList();
            list.preferences = new ArrayList<>(v.values());
        }

        return list;
    }
}