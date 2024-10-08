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
import java.lang.InterruptedException;
import java.lang.Thread;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
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

import org.apache.nutch.protocol.selenium.ChromeEvadeWebClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpWebClient {

  private static final Logger LOG = LoggerFactory
      .getLogger(MethodHandles.lookup().lookupClass());

  public static WebDriver getDriverForPage(String url, Configuration conf, Optional<String> userAgent) {
    WebDriver driver = null;
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
        driver = createChromeWebDriver(chromeDriverPath, enableHeadlessMode, conf, userAgent);
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

      long pageLoadWaitSecs = conf.getLong("page.load.delay", 3);
      driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadWaitSecs));

      long scriptLoadWaitSecs = conf.getLong("script.load.delay", -1);
      if (scriptLoadWaitSecs > -1) {
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(scriptLoadWaitSecs));
      }

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
      boolean enableHeadlessMode, Configuration conf, Optional<String> userAgent) {
    // if not specified, WebDriver will search your path for chromedriver
    System.setProperty("webdriver.chrome.driver", chromeDriverPath);

    String debugLogFile = conf.get("webdriver.chrome.log.debug.file", "");
    if (debugLogFile.length() > 0) {
      LOG.info("Enabling verbose logging of chromedriver at " + debugLogFile);
      System.setProperty("webdriver.chrome.logfile", debugLogFile);
      System.setProperty("webdriver.chrome.verboseLogging", "true");
    }

    ChromeOptions chromeOptions = new ChromeOptions();
    // be sure to set selenium.enable.headless to true if no monitor attached
    // to your server
    if (enableHeadlessMode) {
      chromeOptions.addArguments("--headless");
    }

    String chromeBinary = conf.get("webdriver.chrome.binary", "");
    if (StringUtils.isNotBlank(chromeBinary)) {
      chromeOptions.setBinary(chromeBinary);
    }
    // needed for chromedriver >= 114
    // chromeOptions.addArguments("--remote-allow-origins=*");

    ChromeEvadeWebClient.addArguments(chromeOptions, conf);
    
    WebDriver driver = new ChromeDriver(chromeOptions);

    ChromeEvadeWebClient.enableChromeEvasion((ChromeDriver)driver, conf, userAgent);

    return driver;
  }

  public static RemoteWebDriver createFirefoxRemoteWebDriver(URL seleniumHubUrl,
      boolean enableHeadlessMode) {
    FirefoxOptions firefoxOptions = new FirefoxOptions();
    if (enableHeadlessMode) {
      firefoxOptions.addArguments("--headless");
    }
    RemoteWebDriver driver = new RemoteWebDriver(seleniumHubUrl,
        firefoxOptions);
    return driver;
  }

  public static RemoteWebDriver createChromeRemoteWebDriver(URL seleniumHubUrl,
      boolean enableHeadlessMode) {
    ChromeOptions chromeOptions = new ChromeOptions();
    if (enableHeadlessMode) {
      chromeOptions.addArguments("--headless");
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
   * Function for obtaining the HTML using the selected <a href=
   * 'https://seleniumhq.github.io/selenium/docs/api/java/org/openqa/selenium/WebDriver.html'>selenium
   * webdriver</a> There are a number of configuration properties within
   * <code>nutch-site.xml</code> which determine whether to take screenshots of
   * the rendered pages and persist them as timestamped .png's into HDFS.
   * 
   * @param url
   *          the URL to fetch and render
   * @param conf
   *          the {@link org.apache.hadoop.conf.Configuration}
   * @param userAgent
   *          the userAgent of the HTTP request
   * @return the html page
   */
  public static String getHtmlPage(String url, Configuration conf, Optional<String> userAgent) {
    WebDriver driver = getDriverForPage(url, conf, userAgent);

    // some websites have a delay before they load their content (https://cordis.europa.eu/) which is not handled
    // by selenium's pageLoadTimeout or scriptTimeout
    long driverLoadWaitMillis = conf.getLong("driver.load.delay", -1);
    if (driverLoadWaitMillis > 0) {
      try {
        TimeUnit.MILLISECONDS.sleep(driverLoadWaitMillis);
      } catch (InterruptedException ex) {
        // need to set the interupt flag for others:
        // https://docs.oracle.com/javase/tutorial/essential/concurrency/interrupt.html
        Thread.currentThread().interrupt();
      }
    }

    try {
      if (conf.getBoolean("take.screenshot", false)) {
        takeScreenshot(driver, conf);
      }
      if (conf.getBoolean("evade.page.js.debug", false)) {
        LOG.info("window.outerWidth=" + ((JavascriptExecutor) driver).executeScript("return window.outerWidth;"));
        LOG.info("window.outerHeight=" + ((JavascriptExecutor) driver).executeScript("return window.outerHeight;"));
        LOG.info("navigator.vendor=" + ((JavascriptExecutor) driver).executeScript("return navigator.vendor;"));
        LOG.info("window.Notification=" + ((JavascriptExecutor) driver).executeScript("return window.Notification;"));
        LOG.info("navigator.connection.rtt=" + ((JavascriptExecutor) driver).executeScript("return navigator.connection.rtt;"));
        LOG.info("navigator.maxTouchPoints=" + ((JavascriptExecutor) driver).executeScript("return navigator.maxTouchPoints;"));
        LOG.info("window.chrome=" + ((JavascriptExecutor) driver).executeScript("return window.chrome;"));
        LOG.info("navigator.webdriver=" + ((JavascriptExecutor) driver).executeScript("return navigator.webdriver;"));
        LOG.info("navigator.plugins.length=" + ((JavascriptExecutor) driver).executeScript("return navigator.plugins.length;"));
        LOG.info("navigator.userAgent=" + ((JavascriptExecutor) driver).executeScript("return navigator.userAgent;"));
      }

      return driver.getPageSource();

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
    return getHtmlPage(url, null, Optional.empty());
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
