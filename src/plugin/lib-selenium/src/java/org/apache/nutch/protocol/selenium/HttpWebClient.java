/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nutch.protocol.selenium;

import java.lang.invoke.MethodHandles;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;

import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.JavascriptExecutor;

import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

//import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
//import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.firefox.FirefoxOptions;

import org.openqa.selenium.io.TemporaryFilesystem;

import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

//import org.openqa.selenium.safari.SafariDriver;

//import org.openqa.selenium.phantomjs.PhantomJSDriver;
//import org.openqa.selenium.phantomjs.PhantomJSDriverService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpWebClient {

  private static final Logger LOG = LoggerFactory
      .getLogger(MethodHandles.lookup().lookupClass());

  public static WebDriver getDriverForPage(String url, Configuration conf) {
    WebDriver driver = null;
    long pageLoadWait = conf.getLong("page.load.delay", 3);

    try {
      String driverType = conf.get("selenium.driver", "firefox");
      boolean enableHeadlessMode = conf.getBoolean("selenium.enable.headless",
          false);

      switch (driverType) {
      case "firefox":
        String geckoDriverPath = conf.get("selenium.grid.binary",
            "/root/geckodriver");
        driver = createFirefoxWebDriver(geckoDriverPath, enableHeadlessMode);
        break;
      case "chrome":
        String chromeDriverPath = conf.get("selenium.grid.binary",
            "/root/chromedriver");
        driver = createChromeWebDriver(chromeDriverPath, enableHeadlessMode);
        break;
      case "remote":
        String seleniumHubHost = conf.get("selenium.hub.host", "localhost");
        int seleniumHubPort = Integer
            .parseInt(conf.get("selenium.hub.port", "4444"));
        String seleniumHubPath = conf.get("selenium.hub.path", "/wd/hub");
        String seleniumHubProtocol = conf.get("selenium.hub.protocol", "http");
        URL seleniumHubUrl = new URL(seleniumHubProtocol, seleniumHubHost,
            seleniumHubPort, seleniumHubPath);

        String seleniumGridDriver = conf.get("selenium.grid.driver", "firefox");

        switch (seleniumGridDriver) {
        case "firefox":
          driver = createFirefoxRemoteWebDriver(seleniumHubUrl,
              enableHeadlessMode);
          break;
        case "chrome":
          driver = createChromeRemoteWebDriver(seleniumHubUrl,
              enableHeadlessMode);
          break;
        case "random":
          driver = createRandomRemoteWebDriver(seleniumHubUrl,
              enableHeadlessMode);
          break;
        default:
          LOG.error(
              "The Selenium Grid WebDriver choice {} is not available... defaulting to FirefoxDriver().",
              driverType);
          driver = createDefaultRemoteWebDriver(seleniumHubUrl,
              enableHeadlessMode);
          break;
        }
        break;
      default:
        LOG.error(
            "The Selenium WebDriver choice {} is not available... defaulting to FirefoxDriver().",
            driverType);
        FirefoxOptions options = new FirefoxOptions();
        driver = new FirefoxDriver(options);
        break;
      }
      LOG.debug("Selenium {} WebDriver selected.", driverType);

      driver.manage().timeouts().pageLoadTimeout(pageLoadWait,
          TimeUnit.SECONDS);
      driver.get(url);
    } catch (Exception e) {
      if (e instanceof TimeoutException) {
        LOG.error(
            "Selenium WebDriver: Timeout Exception: Capturing whatever loaded so far...");
        return driver;
      } else {
        LOG.error(e.toString());
      }
      cleanUpDriver(driver);
      throw new RuntimeException(e);
    }

    return driver;
  }

  public static WebDriver createFirefoxWebDriver(String firefoxDriverPath,
      boolean enableHeadlessMode) {
    System.setProperty("webdriver.gecko.driver", firefoxDriverPath);
    FirefoxOptions firefoxOptions = new FirefoxOptions();
    if (enableHeadlessMode) {
      firefoxOptions.addArguments("--headless");
    }
    WebDriver driver = new FirefoxDriver(firefoxOptions);
    return driver;
  }

  public static WebDriver createChromeWebDriver(String chromeDriverPath,
      boolean enableHeadlessMode) {
    // if not specified, WebDriver will search your path for chromedriver
    System.setProperty("webdriver.chrome.driver", chromeDriverPath);
    System.setProperty("webdriver.chrome.logfile", "/home/yarn/chromedriver.log");
    System.setProperty("webdriver.chrome.verboseLogging", "true");

    // taken from https://stackoverflow.com/questions/67617101/how-to-enable-javascript-with-headless-chrome-in-selenium
    ChromeOptions chromeOptions = new ChromeOptions();
    chromeOptions.addArguments("--user-data-dir=/home/yarn/");
    chromeOptions.addArguments("--lang=en-US");
    chromeOptions.addArguments("--no-default-browser-check");
    chromeOptions.addArguments("--no-first-run");
    chromeOptions.addArguments("--no-sandbox");
    chromeOptions.addArguments("--test-type");
    chromeOptions.addArguments("--window-size=1920,1080");
    chromeOptions.addArguments("--start-maximized");

//    chromeOptions.addArguments("--disable-extensions");
//    chromeOptions.addArguments("--enable-javascript");
//    chromeOptions.addArguments("--incognito");
//    chromeOptions.addArguments("--nogpu");
//    chromeOptions.addArguments("--disable-gpu");
//    chromeOptions.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});
//    chromeOptions.addArguments("--disable-blink-features=AutomationControlled");
//    chromeOptions.addArguments("--disable-blink-features");

    // be sure to set selenium.enable.headless to true if no monitor attached
    // to your server
    if (enableHeadlessMode) {
      chromeOptions.addArguments("--headless");
    }
    
    WebDriver driver = new ChromeDriver(chromeOptions);

    ((JavascriptExecutor)driver).executeScript("Object.defineProperty(window, 'navigator', {\n" +
            "                                                    value: new Proxy(navigator, {\n" +
            "                                                            has: (target, key) => (key === 'webdriver' ? false : key in target),\n" +
            "                                                            get: (target, key) =>\n" +
            "                                                                    key === 'webdriver' ?\n" +
            "                                                                    false :\n" +
            "                                                                    typeof target[key] === 'function' ?\n" +
            "                                                                    target[key].bind(target) :\n" +
            "                                                                    target[key]\n" +
            "                                                            })\n" +
            "                                                });");
    ((JavascriptExecutor)driver).executeScript("Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 1});\n" +
            "                            Object.defineProperty(navigator.connection, 'rtt', {get: () => 100});\n" +
            "\n" +
            "                            // https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/chrome-runtime.js\n" +
            "                            window.chrome = {\n" +
            "                                app: {\n" +
            "                                    isInstalled: false,\n" +
            "                                    InstallState: {\n" +
            "                                        DISABLED: 'disabled',\n" +
            "                                        INSTALLED: 'installed',\n" +
            "                                        NOT_INSTALLED: 'not_installed'\n" +
            "                                    },\n" +
            "                                    RunningState: {\n" +
            "                                        CANNOT_RUN: 'cannot_run',\n" +
            "                                        READY_TO_RUN: 'ready_to_run',\n" +
            "                                        RUNNING: 'running'\n" +
            "                                    }\n" +
            "                                },\n" +
            "                                runtime: {\n" +
            "                                    OnInstalledReason: {\n" +
            "                                        CHROME_UPDATE: 'chrome_update',\n" +
            "                                        INSTALL: 'install',\n" +
            "                                        SHARED_MODULE_UPDATE: 'shared_module_update',\n" +
            "                                        UPDATE: 'update'\n" +
            "                                    },\n" +
            "                                    OnRestartRequiredReason: {\n" +
            "                                        APP_UPDATE: 'app_update',\n" +
            "                                        OS_UPDATE: 'os_update',\n" +
            "                                        PERIODIC: 'periodic'\n" +
            "                                    },\n" +
            "                                    PlatformArch: {\n" +
            "                                        ARM: 'arm',\n" +
            "                                        ARM64: 'arm64',\n" +
            "                                        MIPS: 'mips',\n" +
            "                                        MIPS64: 'mips64',\n" +
            "                                        X86_32: 'x86-32',\n" +
            "                                        X86_64: 'x86-64'\n" +
            "                                    },\n" +
            "                                    PlatformNaclArch: {\n" +
            "                                        ARM: 'arm',\n" +
            "                                        MIPS: 'mips',\n" +
            "                                        MIPS64: 'mips64',\n" +
            "                                        X86_32: 'x86-32',\n" +
            "                                        X86_64: 'x86-64'\n" +
            "                                    },\n" +
            "                                    PlatformOs: {\n" +
            "                                        ANDROID: 'android',\n" +
            "                                        CROS: 'cros',\n" +
            "                                        LINUX: 'linux',\n" +
            "                                        MAC: 'mac',\n" +
            "                                        OPENBSD: 'openbsd',\n" +
            "                                        WIN: 'win'\n" +
            "                                    },\n" +
            "                                    RequestUpdateCheckStatus: {\n" +
            "                                        NO_UPDATE: 'no_update',\n" +
            "                                        THROTTLED: 'throttled',\n" +
            "                                        UPDATE_AVAILABLE: 'update_available'\n" +
            "                                    }\n" +
            "                                }\n" +
            "                            }\n" +
            "\n" +
            "                            // https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/navigator-permissions.js\n" +
            "                            if (!window.Notification) {\n" +
            "                                window.Notification = {\n" +
            "                                    permission: 'denied'\n" +
            "                                }\n" +
            "                            }\n" +
            "\n" +
            "                            const originalQuery = window.navigator.permissions.query\n" +
            "                            window.navigator.permissions.__proto__.query = parameters =>\n" +
            "                                parameters.name === 'notifications'\n" +
            "                                    ? Promise.resolve({ state: window.Notification.permission })\n" +
            "                                    : originalQuery(parameters)\n" +
            "\n" +
            "                            const oldCall = Function.prototype.call\n" +
            "                            function call() {\n" +
            "                                return oldCall.apply(this, arguments)\n" +
            "                            }\n" +
            "                            Function.prototype.call = call\n" +
            "\n" +
            "                            const nativeToStringFunctionString = Error.toString().replace(/Error/g, 'toString')\n" +
            "                            const oldToString = Function.prototype.toString\n" +
            "\n" +
            "                            function functionToString() {\n" +
            "                                if (this === window.navigator.permissions.query) {\n" +
            "                                    return 'function query() { [native code] }'\n" +
            "                                }\n" +
            "                                if (this === functionToString) {\n" +
            "                                    return nativeToStringFunctionString\n" +
            "                                }\n" +
            "                                return oldCall.call(oldToString, this)\n" +
            "                            }\n" +
            "                            // eslint-disable-next-line\n" +
            "                            Function.prototype.toString = functionToString");
    ((JavascriptExecutor)driver).executeScript("let objectToInspect = window,\n" +
            "                        result = [];\n" +
            "                    while(objectToInspect !== null)\n" +
            "                    { result = result.concat(Object.getOwnPropertyNames(objectToInspect));\n" +
            "                      objectToInspect = Object.getPrototypeOf(objectToInspect); }\n" +
            "                    result.forEach(p => p.match(/.+_.+_(Array|Promise|Symbol)/ig)\n" +
            "                                        &&delete window[p]&&console.log('removed',p))");
//    ((JavascriptExecutor)driver).executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
//    ((JavascriptExecutor)driver).executeScript("Object.defineProperty(Navigator.prototype, 'webdriver', {\n" +
//            "        set: undefined,\n" +
//            "        enumerable: true,\n" +
//            "        configurable: true,\n" +
//            "        get: new Proxy(\n" +
//            "            Object.getOwnPropertyDescriptor(Navigator.prototype, 'webdriver').get,\n" +
//            "            { apply: (target, thisArg, args) => {\n" +
//            "                // emulate getter call validation\n" +
//            "                Reflect.apply(target, thisArg, args);\n" +
//            "                return false;\n" +
//            "            }}\n" +
//            "        )\n" +
//            "    });");
    return driver;
  }

  public static RemoteWebDriver createFirefoxRemoteWebDriver(URL seleniumHubUrl,
      boolean enableHeadlessMode) {
    FirefoxOptions firefoxOptions = new FirefoxOptions();
    if (enableHeadlessMode) {
      firefoxOptions.setHeadless(true);
    }
    RemoteWebDriver driver = new RemoteWebDriver(seleniumHubUrl,
        firefoxOptions);
    return driver;
  }

  public static RemoteWebDriver createChromeRemoteWebDriver(URL seleniumHubUrl,
      boolean enableHeadlessMode) {
    ChromeOptions chromeOptions = new ChromeOptions();
    if (enableHeadlessMode) {
      chromeOptions.setHeadless(true);
    }
    RemoteWebDriver driver = new RemoteWebDriver(seleniumHubUrl, chromeOptions);
    return driver;
  }

  public static RemoteWebDriver createRandomRemoteWebDriver(URL seleniumHubUrl,
      boolean enableHeadlessMode) {
    // we consider a possibility of generating only 2 types of browsers: Firefox
    // and
    // Chrome only
    Random r = new Random();
    int min = 0;
    // we have actually hardcoded the maximum number of types of web driver that
    // can
    // be created
    // but this must be later moved to the configuration file in order to be
    // able
    // to randomly choose between much more types(ex: Edge, Opera, Safari)
    int max = 1; // for 3 types, change to 2 and update the if-clause
    int num = r.nextInt((max - min) + 1) + min;
    if (num == 0) {
      return createFirefoxRemoteWebDriver(seleniumHubUrl, enableHeadlessMode);
    }

    return createChromeRemoteWebDriver(seleniumHubUrl, enableHeadlessMode);
  }

  public static RemoteWebDriver createDefaultRemoteWebDriver(URL seleniumHubUrl,
      boolean enableHeadlessMode) {
    return createFirefoxRemoteWebDriver(seleniumHubUrl, enableHeadlessMode);
  }

  public static void cleanUpDriver(WebDriver driver) {
    if (driver != null) {
      try {
        // driver.close();
        driver.quit();
        TemporaryFilesystem.getDefaultTmpFS().deleteTemporaryFiles();
      } catch (Exception e) {
        LOG.error(e.toString());
        // throw new RuntimeException(e);
      }
    }
  }

  /**
   * Function for obtaining the HTML BODY using the selected <a href=
   * 'https://seleniumhq.github.io/selenium/docs/api/java/org/openqa/selenium/WebDriver.html'>selenium
   * webdriver</a> There are a number of configuration properties within
   * <code>nutch-site.xml</code> which determine whether to take screenshots of
   * the rendered pages and persist them as timestamped .png's into HDFS.
   * 
   * @param url
   *          the URL to fetch and render
   * @param conf
   *          the {@link org.apache.hadoop.conf.Configuration}
   * @return the rendered inner HTML page
   */
  public static String getHtmlPage(String url, Configuration conf) {
    WebDriver driver = getDriverForPage(url, conf);

    try {
      if (conf.getBoolean("take.screenshot", false)) {
        takeScreenshot(driver, conf);
      }

//      new Actions(driver).moveByOffset(13, 15).perform();

      LOG.info("Kamil");
      // https://stackoverflow.com/a/55972011/1000361
      ((JavascriptExecutor)driver).executeScript("window.key = \"blahblah\";");

      LOG.info("kamil window.key=" + ((JavascriptExecutor)driver).executeScript("return window.key;"));
      LOG.info("kamil navigator.webdriver=" + ((JavascriptExecutor)driver).executeScript("return navigator.webdriver;"));
      LOG.info("kamil navigator.plugins.length=" + ((JavascriptExecutor)driver).executeScript("return navigator.plugins.length;"));
      LOG.info("kamil navigator.languages=" + ((JavascriptExecutor)driver).executeScript("return navigator.languages;"));

      String innerHtml = driver.findElement(By.tagName("body"))
          .getAttribute("innerHTML");
      return innerHtml;

      // I'm sure this catch statement is a code smell ; borrowing it from
      // lib-htmlunit
    } catch (Exception e) {
      TemporaryFilesystem.getDefaultTmpFS().deleteTemporaryFiles();
      // throw new RuntimeException(e);
      LOG.error("getHtmlPage(url, conf): " + e.toString());
      throw new RuntimeException(e);
    } finally {
      cleanUpDriver(driver);
    }
  }

  public static String getHtmlPage(String url) {
    return getHtmlPage(url, null);
  }

  private static void takeScreenshot(WebDriver driver, Configuration conf) {
    try {
      String url = driver.getCurrentUrl();
      File srcFile = ((TakesScreenshot) driver)
          .getScreenshotAs(OutputType.FILE);
      LOG.debug("In-memory screenshot taken of: {}", url);
      FileSystem fs = FileSystem.get(conf);
      if (conf.get("screenshot.location") != null) {
        Path screenshotPath = new Path(
            conf.get("screenshot.location") + "/" + srcFile.getName());
        OutputStream os = null;
        if (!fs.exists(screenshotPath)) {
          LOG.debug(
              "No existing screenshot already exists... creating new file at {} {}.",
              screenshotPath, srcFile.getName());
          os = fs.create(screenshotPath);
        }
        InputStream is = new BufferedInputStream(new FileInputStream(srcFile));
        IOUtils.copyBytes(is, os, conf);
        LOG.debug("Screenshot for {} successfully saved to: {} {}", url,
            screenshotPath, srcFile.getName());
      } else {
        LOG.warn(
            "Screenshot for {} not saved to HDFS (subsequently disgarded) as value for "
                + "'screenshot.location' is absent from nutch-site.xml.",
            url);
      }
    } catch (Exception e) {
      LOG.error("Error taking screenshot: ", e);
      cleanUpDriver(driver);
      throw new RuntimeException(e);
    }
  }
}
