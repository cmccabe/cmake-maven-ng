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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Goal which runs a native unit test.
 *
 * @goal test
 * @phase test
 */
public class TestMojo extends AbstractMojo {
  /**
   * Location of the binary to run.
   *
   * @parameter expression="${binary}"
   * @required
   */
  private File binary;

  /**
   * Name of this test.
   *
   * Defaults to the basename of the binary.  So if your binary is /foo/bar/baz,
   * this will default to 'baz.'
   *
   * @parameter expression="${testName}"
   */
  private String testName;

  /**
   * Environment variables to pass to the binary.
   *
   * @parameter expression="${env}"
   */
  private Map<String, String> env;

  /**
   * Arguments to pass to the binary.
   *
   * @parameter expression="${args}"
   */
  private List<String> args;

  /**
   * Number of seconds to wait before declaring the test failed.
   *
   * @parameter expression="${timeout}" default-value=600
   */
  private int timeout;

  /**
   * Path to results directory.
   *
   * @parameter expression="${results}" default-value="./cmake-ng-results"
   */
  private File results;

  /**
   * The test thread waits for the process to terminate.
   *
   * Since Process#waitFor doesn't take a timeout argument, we simulate one by
   * interrupting this thread after a certain amount of time has elapsed.
   */
  private static class TestThread extends Thread {
    private Process proc;
    private int retCode = -1;

    public TestThread(Process proc) {
      this.proc = proc;
    }

    public void run() {
      try {
        retCode = proc.waitFor();
      } catch (InterruptedException e) {
        retCode = -1;
      }
    }

    public int retCode() {
      return retCode;
    }
  }

  /**
   * Redirect a pipe to a file.
   * 
   * Java 5 and 6 don't offer a way to redirect ProcessBuilder's stdout and
   * stderr to a file.  Instead, they give you a way to access the output as
   * a pipe.  However, a thread can redirect 
   * the output to a file
   * 
   * @author cmccabe
   *
   */
  private static class RedirectorThread extends Thread {
    private BufferedReader rd;
    private Writer wr;
    
    public RedirectorThread(InputStream rd, File outFile) 
        throws IOException {
      FileOutputStream fos = new FileOutputStream(outFile);
      try {
        this.wr = new BufferedWriter(new OutputStreamWriter(fos));
        this.rd = new BufferedReader(new InputStreamReader(rd));
        fos = null;
      } finally {
        if (fos != null) {
          fos.close();
        }
      }
    }
    
    @Override
    public void run() {
      try {
        String line = rd.readLine();
        while((line != null) && !isInterrupted()) {
          wr.append(line);
          wr.append(System.getProperty("line.separator"));
          line = rd.readLine();
        }
      } catch(IOException ioe) {
      } finally {
        try {
          wr.close();
        } catch (IOException e) {
          e.printStackTrace(System.err);
        }
        try {
          rd.close();
        } catch (IOException e) {
          e.printStackTrace(System.err);
        }
      }
    }
  }
  
  /**
   * Write to the status file.
   *
   * The status file will contain a string describing the exit status of the
   * test.  It will be SUCCESS if the test returned success (return code 0), a
   * numerical code if it returned a non-zero status, or IN_PROGRESS or
   * TIMED_OUT.
   */
  void writeStatusFile(String status) throws IOException {
    FileOutputStream fos = new FileOutputStream(new File(results,
                testName + ".status"));
    try {
      BufferedWriter out = new BufferedWriter(new OutputStreamWriter(fos));
      out.write(status + "\n");
    } finally {
      fos.close();
    }
  }

  public void execute() throws MojoExecutionException {
    Utils.validatePlatform();

    if (!binary.exists()) {
      throw new MojoExecutionException("Test " + binary +
          " was not built!  (File does not exist.)");
    }
    if (!results.isDirectory()) {
      if (!results.mkdirs()) {
        throw new MojoExecutionException("Failed to create " +
            "output directory '" + results + "'!");
      }
    }
    if (testName == null) {
      testName = binary.getName();
    }
    List<String> cmd = new LinkedList<String>();
    cmd.add(binary.getAbsolutePath());
    for (String entry : args) {
      cmd.add(entry);
    }
    ProcessBuilder pb = new ProcessBuilder(cmd);
    Utils.addEnvironment(pb, env);
    Process proc = null;
    TestThread testThread = null;
    Thread errThread = null, outThread = null;
    int retCode = -1;
    String status = "IN_PROGRESS";
    try {
      writeStatusFile(status);
    } catch (IOException e) {
      throw new MojoExecutionException("Error writing the status file", e);
    }
    try {
      proc = pb.start();
      errThread = new RedirectorThread(proc.getErrorStream(),
          new File(results, testName + ".stderr"));
      errThread.start();
      // Process#getInputStream gets the stdout stream of the process, which 
      // acts as an input to us.
      outThread = new RedirectorThread(proc.getInputStream(),
          new File(results, testName + ".stdout"));
      outThread.start();
      testThread = new TestThread(proc);
      testThread.start();
      testThread.join(timeout * 1000);
      retCode = testThread.retCode();
      testThread = null;
      proc = null;
    } catch (IOException e) {
      throw new MojoExecutionException("IOException while executing the test " +
          testName, e);
    } catch (InterruptedException e) {
      throw new MojoExecutionException("Interrupted while executing " + 
          "the test " + testName, e);
    } finally {
      if (testThread != null) {
        // If the test thread didn't exit yet, that means the timeout expired.
        testThread.interrupt();
        try {
          testThread.join();
        } catch (InterruptedException e) {
          System.err.println("Interrupted while waiting for testThread");
          e.printStackTrace(System.err);
        }
        status = "TIMED_OUT";
      } else if (retCode == 0) {
        status = "SUCCESS";
      } else {
        status = "ERROR " + String.valueOf(retCode);
      }
      try {
        writeStatusFile(status);
      } catch (Exception e) {
        System.err.println("failed to write status file!  Error " + e);
      }
      if (proc != null) {
        proc.destroy();
      }
      // Now that we've terminated the process, the threads servicing
      // its pipes should receive end-of-file and exit.
      // We don't want to terminate them manually or else we might lose
      // some output.
      if (errThread != null) {
        try {
          errThread.join();
        } catch (InterruptedException e) {
          System.err.println("Interrupted while waiting for errThread");
          e.printStackTrace(System.err);
        }
      }
      if (outThread != null) {
        try {
          outThread.join();
        } catch (InterruptedException e) {
          System.err.println("Interrupted while waiting for outThread");
          e.printStackTrace(System.err);
        }
      }
    }
    if (status.equals("TIMED_OUT")) {
      throw new MojoExecutionException("Test " + binary +
          " timed out after " + timeout + " seconds!");
    } else if (!status.equals("SUCCESS")) {
      // TODO: do we need to do something special here to handle the case where
      // we're doing mvn --ff or never-fail, etc.?  Probably not...
      throw new MojoExecutionException("Test " + binary +
          " returned " + status);
    }
  }
}
