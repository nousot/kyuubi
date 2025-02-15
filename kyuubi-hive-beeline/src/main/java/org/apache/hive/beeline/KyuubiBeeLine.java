/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.beeline;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Driver;
import java.util.*;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hive.common.util.HiveStringUtils;

public class KyuubiBeeLine extends BeeLine {

  static {
    try {
      // We use reflection here to handle the case where users remove the
      // slf4j-to-jul bridge order to route their logs to JUL.
      Class<?> bridgeClass = Class.forName("org.slf4j.bridge.SLF4JBridgeHandler");
      bridgeClass.getMethod("removeHandlersForRootLogger").invoke(null);
      boolean installed = (boolean) bridgeClass.getMethod("isInstalled").invoke(null);
      if (!installed) {
        bridgeClass.getMethod("install").invoke(null);
      }
    } catch (ReflectiveOperationException cnf) {
      // can't log anything yet so just fail silently
    }
  }

  public static final String KYUUBI_BEELINE_DEFAULT_JDBC_DRIVER =
      "org.apache.kyuubi.jdbc.KyuubiHiveDriver";
  protected KyuubiCommands commands = new KyuubiCommands(this);
  private Driver defaultDriver = null;

  // copied from org.apache.hive.beeline.BeeLine
  private static final int ERRNO_OK = 0;
  private static final int ERRNO_ARGS = 1;
  private static final int ERRNO_OTHER = 2;

  private static final String PYTHON_MODE_PREFIX = "--python-mode";
  private boolean pythonMode = false;

  public KyuubiBeeLine() {
    this(true);
  }

  @SuppressWarnings("deprecation")
  public KyuubiBeeLine(boolean isBeeLine) {
    super(isBeeLine);
    try {
      Field commandsField = BeeLine.class.getDeclaredField("commands");
      commandsField.setAccessible(true);
      commandsField.set(this, commands);
    } catch (Throwable t) {
      throw new ExceptionInInitializerError("Failed to inject kyuubi commands");
    }
    try {
      defaultDriver =
          (Driver)
              Class.forName(
                      KYUUBI_BEELINE_DEFAULT_JDBC_DRIVER,
                      true,
                      Thread.currentThread().getContextClassLoader())
                  .newInstance();
    } catch (Throwable t) {
      throw new ExceptionInInitializerError(KYUUBI_BEELINE_DEFAULT_JDBC_DRIVER + "-missing");
    }
  }

  @Override
  void usage() {
    super.usage();
    output("Usage: java \" + KyuubiBeeLine.class.getCanonicalName()");
    output("   --python-mode                   Execute python code/script.");
  }

  public boolean isPythonMode() {
    return pythonMode;
  }

  // Visible for testing
  public void setPythonMode(boolean pythonMode) {
    this.pythonMode = pythonMode;
  }

  /** Starts the program. */
  public static void main(String[] args) throws IOException {
    mainWithInputRedirection(args, null);
  }

  /**
   * Starts the program with redirected input. For redirected output, setOutputStream() and
   * setErrorStream can be used. Exits with 0 on success, 1 on invalid arguments, and 2 on any other
   * error
   *
   * @param args same as main()
   * @param inputStream redirected input, or null to use standard input
   */
  public static void mainWithInputRedirection(String[] args, InputStream inputStream)
      throws IOException {
    KyuubiBeeLine beeLine = new KyuubiBeeLine();
    try {
      int status = beeLine.begin(args, inputStream);

      if (!Boolean.getBoolean(BeeLineOpts.PROPERTY_NAME_EXIT)) {
        System.exit(status);
      }
    } finally {
      beeLine.close();
    }
  }

  protected Driver getDefaultDriver() {
    return defaultDriver;
  }

  @Override
  String getApplicationTitle() {
    Package pack = BeeLine.class.getPackage();

    return loc(
        "app-introduction",
        new Object[] {
          "Beeline",
          pack.getImplementationVersion() == null ? "???" : pack.getImplementationVersion(),
          "Apache Kyuubi",
        });
  }

  @Override
  int initArgs(String[] args) {
    List<String> commands = Collections.emptyList();

    CommandLine cl;
    BeelineParser beelineParser;
    boolean connSuccessful;
    boolean exit;
    Field exitField;

    try {
      Field optionsField = BeeLine.class.getDeclaredField("options");
      optionsField.setAccessible(true);
      Options options = (Options) optionsField.get(this);

      beelineParser =
          new BeelineParser() {
            @Override
            protected void processOption(String arg, ListIterator iter) throws ParseException {
              if (PYTHON_MODE_PREFIX.equals(arg)) {
                pythonMode = true;
              } else {
                super.processOption(arg, iter);
              }
            }
          };
      cl = beelineParser.parse(options, args);

      Method connectUsingArgsMethod =
          BeeLine.class.getDeclaredMethod(
              "connectUsingArgs", BeelineParser.class, CommandLine.class);
      connectUsingArgsMethod.setAccessible(true);
      connSuccessful = (boolean) connectUsingArgsMethod.invoke(this, beelineParser, cl);

      exitField = BeeLine.class.getDeclaredField("exit");
      exitField.setAccessible(true);
      exit = (boolean) exitField.get(this);

    } catch (ParseException e1) {
      output(e1.getMessage());
      usage();
      return -1;
    } catch (Exception t) {
      error(t.getMessage());
      return 1;
    }

    // checks if default hs2 connection configuration file is present
    // and uses it to connect if found
    // no-op if the file is not present
    if (!connSuccessful && !exit) {
      try {
        Method defaultBeelineConnectMethod =
            BeeLine.class.getDeclaredMethod("defaultBeelineConnect", CommandLine.class);
        defaultBeelineConnectMethod.setAccessible(true);
        connSuccessful = (boolean) defaultBeelineConnectMethod.invoke(this, cl);

      } catch (Exception t) {
        error(t.getMessage());
        return 1;
      }
    }

    // see HIVE-19048 : InitScript errors are ignored
    if (exit) {
      return 1;
    }

    int code = 0;
    if (cl.getOptionValues('e') != null) {
      commands = Arrays.asList(cl.getOptionValues('e'));
      // When using -e, command is always a single line, see HIVE-19018
      getOpts().setAllowMultiLineCommand(false);
    }

    if (!commands.isEmpty() && getOpts().getScriptFile() != null) {
      error("The '-e' and '-f' options cannot be specified simultaneously");
      return 1;
    } else if (!commands.isEmpty() && !connSuccessful) {
      error("Cannot run commands specified using -e. No current connection");
      return 1;
    }
    if (!commands.isEmpty()) {
      for (Iterator<String> i = commands.iterator(); i.hasNext(); ) {
        String command = i.next().toString();
        debug(loc("executing-command", command));
        if (!dispatch(command)) {
          code++;
        }
      }
      try {
        exit = true;
        exitField.set(this, exit);
      } catch (Exception e) {
        error(e.getMessage());
        return 1;
      }
    }
    return code;
  }

  // see HIVE-19048 : Initscript errors are ignored
  @Override
  int runInit() {
    String[] initFiles = getOpts().getInitFiles();

    // executionResult will be ERRNO_OK only if all initFiles execute successfully
    int executionResult = ERRNO_OK;
    boolean exitOnError = !getOpts().getForce();
    Field exitField = null;

    if (initFiles != null && initFiles.length != 0) {
      for (String initFile : initFiles) {
        info("Running init script " + initFile);
        try {
          int currentResult;
          try {
            Method executeFileMethod = BeeLine.class.getDeclaredMethod("executeFile", String.class);
            executeFileMethod.setAccessible(true);
            currentResult = (int) executeFileMethod.invoke(null, initFile);
            exitField = BeeLine.class.getDeclaredField("exit");
            exitField.setAccessible(true);
          } catch (Exception t) {
            error(t.getMessage());
            currentResult = ERRNO_OTHER;
          }

          if (currentResult != ERRNO_OK) {
            executionResult = currentResult;

            if (exitOnError) {
              return executionResult;
            }
          }
        } finally {
          // exit beeline if there is initScript failure and --force is not set
          boolean exit = exitOnError && executionResult != ERRNO_OK;
          try {
            exitField.set(this, exit);
          } catch (Exception t) {
            error(t.getMessage());
            return ERRNO_OTHER;
          }
        }
      }
    }
    return executionResult;
  }

  // see HIVE-15820: comment at the head of beeline -e
  @Override
  boolean dispatch(String line) {
    return super.dispatch(isPythonMode() ? line : HiveStringUtils.removeComments(line));
  }
}
