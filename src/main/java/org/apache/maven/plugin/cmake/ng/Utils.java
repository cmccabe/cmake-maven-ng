package org.apache.maven.plugin.cmake.ng;

/*
 * Copyright 2012 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Utilities.
 */
public class Utils {
  static void validatePlatform() throws MojoExecutionException {
    if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
      throw new MojoExecutionException("CMake-NG does not (yet) support " +
          "the Windows platform.");
    }
  }

  /**
   * Validate that the parameters look sane.
   */
  static void validateParams(File output, File source)
      throws MojoExecutionException {
    String cOutput = null, cSource = null;
    try {
      cOutput = output.getCanonicalPath();
    } catch (IOException e) {
      throw new MojoExecutionException("error getting canonical path " +
          "for output");
    }
    try {
      cSource = source.getCanonicalPath();
    } catch (IOException e) {
      throw new MojoExecutionException("error getting canonical path " +
          "for source");
    }
    
    // This doesn't catch all the bad cases-- we could be following symlinks or
    // hardlinks, etc.  However, this will usually catch a common mistake.
    if (cSource.startsWith(cOutput)) {
      throw new MojoExecutionException("The source directory must not be " +
          "inside the output directory (it would be destroyed by " +
          "'mvn clean')");
    }
  }

  /**
   * Add environment variables to a ProcessBuilder.
   */
  static void addEnvironment(ProcessBuilder pb, Map<String, String> env) {
    Map<String, String> processEnv = pb.environment();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      String val = entry.getValue();
      if (val == null) {
        val = "";
      }
      processEnv.put(entry.getKey(), val);
    }
  }
}
