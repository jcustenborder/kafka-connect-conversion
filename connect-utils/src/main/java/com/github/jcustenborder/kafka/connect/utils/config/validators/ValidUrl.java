/**
 * Copyright © 2018 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.utils.config.validators;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

public class ValidUrl implements ConfigDef.Validator {

  static void validate(String config, String value) {
    try {
      new URL(value);
    } catch (MalformedURLException e) {
      ConfigException configException = new ConfigException(
          config, value, "Could not parse to URL."
      );
      configException.initCause(e);

      throw configException;
    }
  }

  @Override
  public void ensureValid(String config, Object value) {
    if (value instanceof String) {
      validate(config, (String) value);
    } else if (value instanceof List) {
      List<String> values = (List<String>) value;
      for (String v : values) {
        validate(config, v);
      }
    } else {
      throw new ConfigException(config, value, "Must be a string or list.");
    }
  }

  @Override
  public String toString() {
    return "";
  }
}
