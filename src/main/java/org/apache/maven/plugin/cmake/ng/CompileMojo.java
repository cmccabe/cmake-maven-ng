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
import org.apache.maven.plugin.cmake.ng.Utils.OutputBufferThread;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Goal which builds the native sources
 *
 * @goal compile
 * @phase compile
 */
public class CompileMojo extends AbstractMojo {
  /**
   * Location of the build products.
   *
   * @parameter expression="${output}"
   * @required
   */
  private File output;

  /**
   * Build target.
   *
   * @parameter expression="${target}"
   */
  private String target;

  public void execute() throws MojoExecutionException {
    Utils.validatePlatform();

    List<String> cmd = new LinkedList<String>();
    cmd.add("make");
    cmd.add("VERBOSE=1");
    if (target != null) {
      cmd.add(target);
    }
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(output);
    Process proc = null;
    int retCode = -1;
    OutputBufferThread stdoutThread = null, stderrThread = null;
    try {
      proc = pb.start();
      stdoutThread = new OutputBufferThread(proc.getInputStream());
      stderrThread = new OutputBufferThread(proc.getErrorStream());
      stdoutThread.start();
      stderrThread.start();
      retCode = proc.waitFor();
      if (retCode != 0) {
        throw new MojoExecutionException("make failed with error code " +
            retCode);
      }
    } catch (InterruptedException e) {
      throw new MojoExecutionException("Interrupted during Process#waitFor", e);
    } catch (IOException e) {
      throw new MojoExecutionException("Error executing make", e);
    } finally {
      if (stdoutThread != null) {
        try {
          stdoutThread.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if (retCode != 0) {
          stdoutThread.printBufs();
        }
      }
      if (stderrThread != null) {
        try {
          stderrThread.join();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        // We always print stderr, since it contains the compiler warning
        // messages.  These are interesting even if compilation succeeded.
        stderrThread.printBufs();
      }
      proc.destroy();
    }
  }
}
