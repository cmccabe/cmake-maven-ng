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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Goal which runs 'cmake' to create the native build directory.
 *
 * @goal generate
 * @phase process-sources
 */
public class GenerateMojo extends AbstractMojo {
  /**
   * Location of the build products.
   *
   * @parameter expression="${output}"
   * @required
   */
  private File output;

  /**
   * Location of the source files.
   * This should be where the sources are checked in.
   *
   * @parameter expression="${source}"
   * @required
   */
  private File source;

  /**
   * Environment variables to pass to CMake.
   *
   * Note that it is usually better to use a CMake variable than an environment
   * variable.  To quote the CMake FAQ:
   *
   * "One should avoid using environment variables for controlling the flow of
   * CMake code (such as in IF commands). The build system generated by CMake
   * may re-run CMake automatically when CMakeLists.txt files change. The
   * environment in which this is executed is controlled by the build system and
   * may not match that in which CMake was originally run. If you want to
   * control build settings on the CMake command line, you need to use cache
   * variables set with the -D option. The settings will be saved in
   * CMakeCache.txt so that they don't have to be repeated every time CMake is
   * run on the same build tree."
   *
   * @parameter expression="${env}"
   */
  private Map<String, String> env;

  /**
   * CMake cached variables to set.
   *
   * @parameter expression="${vars}"
   */
  private Map<String, String> vars;

  private static class RedirectorThread extends Thread {
    private BufferedReader rd;

    public RedirectorThread(InputStream rd) {
      this.rd = new BufferedReader(new InputStreamReader(rd));
    }
    
    @Override
    public void run() {
      try {
        String line = rd.readLine();
        while((line != null) && !isInterrupted()) {
          System.out.println(line);
          line = rd.readLine();
        }
      } catch (IOException e) {
      }
    }
  }

  public void execute() throws MojoExecutionException {
    Utils.validatePlatform();
    Utils.validateParams(output, source);

    output.mkdirs();
    List<String> cmd = new LinkedList<String>();
    cmd.add("cmake");
    cmd.add(source.getAbsolutePath());
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      if ((entry.getValue() == null) || (entry.getValue().equals(""))) {
        cmd.add("-D" + entry.getKey());
      } else {
        cmd.add("-D" + entry.getKey() + "=" + entry.getValue());
      }
    }
    cmd.add("-G");
    cmd.add("Unix Makefiles");
    String prefix = "";
    StringBuilder bld = new StringBuilder();
    for (String c : cmd) {
      bld.append(prefix);
      bld.append("'").append(c).append("'");
      prefix = " ";
    }
    System.out.println("Running " + bld.toString());
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(output);
    pb.redirectErrorStream(true);
    Utils.addEnvironment(pb, env);
    Process proc = null;
    RedirectorThread stdoutThread = null;
    int retCode = -1;
    try {
      proc = pb.start();
      stdoutThread = new RedirectorThread(proc.getInputStream());
      stdoutThread.start();

      retCode = proc.waitFor();
      if (retCode != 0) {
        throw new MojoExecutionException("CMake failed with error code " +
            retCode);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Error executing CMake", e);
    } catch (InterruptedException e) {
      throw new MojoExecutionException("Interrupted while waiting for " +
          "CMake process", e);
    } finally {
      if (proc != null) {
        proc.destroy();
      }
      if (stdoutThread != null) {
        try {
          stdoutThread.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }
}
