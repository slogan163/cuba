/*
 * Copyright (c) 2008-2017 Haulmont.
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

package com.haulmont.cuba.core.entity;

import com.haulmont.cuba.core.global.AppBeans;
import com.haulmont.cuba.core.global.QueryUtils;
import com.haulmont.cuba.core.global.UserSessionSource;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public final class LocaleHelper {

    private LocaleHelper() {
    }

    public static String getLocalizedName(String localeBundle) {
        Locale locale = AppBeans.get(UserSessionSource.class).getLocale();
        String localeName = null;
        if (StringUtils.isNotEmpty(localeBundle)) {
            // find locale name
            StringReader reader = new StringReader(localeBundle);
            Properties localeProperties = new Properties();
            boolean localeLoaded = false;
            try {
                localeProperties.load(reader);
                localeLoaded = true;
            } catch (IOException ignored) {
            }
            if (localeLoaded) {
                String key = locale.getLanguage();
                if (StringUtils.isNotEmpty(locale.getCountry()))
                    key += "_" + locale.getCountry();
                if (localeProperties.containsKey(key))
                    localeName = (String) localeProperties.get(key);
            }
        }
        return localeName;
    }

    public static Map<String, String> getLocalizedNames(String localeBundle) {
        if (StringUtils.isNotEmpty(localeBundle)) {
            StringReader reader = new StringReader(localeBundle);
            Properties localeProperties = new Properties();
            boolean localeLoaded = false;
            try {
                localeProperties.load(reader);
                localeLoaded = true;
            } catch (IOException ignored) {
            }
            if (localeLoaded) {
                Map<String, String> map = new HashMap<>();
                for (Map.Entry<Object, Object> entry : localeProperties.entrySet()) {
                    map.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                return map;
            }
        }
        return Collections.emptyMap();
    }
}