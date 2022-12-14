package org.apache.nutch.protocol.selenium;

import java.lang.invoke.MethodHandles;
import java.util.Optional;

import org.apache.hadoop.conf.Configuration;
import org.apache.commons.lang.StringUtils;

import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.v108.log.Log;
import org.openqa.selenium.devtools.v108.page.Page;
import org.openqa.selenium.devtools.v108.log.model.LogEntry;
import org.openqa.selenium.devtools.v108.runtime.model.CallFrame;
import org.openqa.selenium.devtools.v108.network.Network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChromeEvadeWebClient {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /**
     * taken from https://stackoverflow.com/questions/67617101/how-to-enable-javascript-with-headless-chrome-in-selenium
     */
    public static void addArguments(ChromeOptions chromeOptions, Configuration conf) {
        String userDataDir = conf.get("webdriver.chrome.user.data.dir", "");
        if(StringUtils.isBlank(userDataDir)) {
            LOG.warn("No user data dir for chrome");
        } else {
            LOG.info("Using chrome user data dir " + userDataDir);
            chromeOptions.addArguments("--user-data-dir=" + userDataDir);
        }

        chromeOptions.addArguments("--lang=en-US");
        chromeOptions.addArguments("--no-default-browser-check");
        chromeOptions.addArguments("--no-first-run");
        chromeOptions.addArguments("--no-sandbox");
        chromeOptions.addArguments("--test-type");
        chromeOptions.addArguments("--window-size=1920,1080");
        chromeOptions.addArguments("--start-maximized");
        chromeOptions.addArguments("--disable-blink-features=AutomationControlled");
        chromeOptions.addArguments("--disable-blink-features");
        chromeOptions.setExperimentalOption("excludeSwitches", new String[] {"enable-automation"});

//        chromeOptions.addArguments("--disable-extensions");
//        chromeOptions.addArguments("--incognito");
//        chromeOptions.addArguments("--disable-gpu");
    }

    /**
     * Configures the browser to evade a bot detection websites.
     * @param chromeDriver
     */
    public static void enableChromeEvasion(ChromeDriver chromeDriver, Configuration conf, Optional<String> userAgent) {
        DevTools devTools = chromeDriver.getDevTools();
        devTools.createSession();

        if(conf.getBoolean("webdriver.chrome.console.log.output", false)) {
            LOG.info("Enabling logging of console output in Chrome.");
            // we need to enable the domains we intend to use
            devTools.send(Log.enable());

            // log devtools logging
            devTools.addListener(Log.entryAdded(),
                logEntry -> {
                    LOG.info("Chrome DevTools Log: [" + logEntry.getLevel() + "] " + logEntry.getText());
                }
            );
        }

        if (conf.getBoolean("webdriver.chrome.js.exception.output", false)) {
            LOG.info("Enabling logging of JS exceptions in Chrome.");
            devTools.getDomains().events().addJavascriptExceptionListener(jsException -> {
                LOG.error("Chrome Devtools JS exception: " + jsException.getMessage());
                jsException.printStackTrace();
            });
        }

        if(userAgent.isPresent()) {
            devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));
            overrideUserAgent(devTools, userAgent.get());
        }

        devTools.send(Page.enable());
        hideWebdriver(devTools);
        hideNavigator(devTools);
        overridePrototypes(devTools);
        removeCDCProps(devTools);
        mockPlugins(devTools);
        mockWindowFrame(devTools);
    }

    /***
     *
     * Overrides window.navigator.webdriver.
     *
     * Credit for JS goes to https://github.com/ultrafunkamsterdam/undetected-chromedriver/blob/master/undetected_chromedriver/__init__.py
     * @param devTools
     */
    private static void hideWebdriver(DevTools devTools) {
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
            "Object.defineProperty(window, 'navigator', {\n" +
            "  value: new Proxy(navigator, {\n" +
            "  has: (target, key) => (key === 'webdriver' ? false : key in target),\n" +
            "  get: (target, key) =>\n" +
            "    key === 'webdriver' ?\n" +
            "    false :\n" +
            "    typeof target[key] === 'function' ?\n" +
            "    target[key].bind(target) :\n" +
            "    target[key]\n" +
            "  })\n" +
            "});",
            Optional.empty(),
            Optional.empty())
        );
    }

    /***
     *
     * Overrides window.navigator.permissions, navigator.maxTouchPoints, navigator.connection.rtt
     * and window.Notification.
     *
     * Credit for JS goes to https://github.com/ultrafunkamsterdam/undetected-chromedriver/blob/master/undetected_chromedriver/__init__.py
     * with original credit to:
     * - https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/navigator-permissions.js
     * - https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/chrome-runtime.js
     */
    private static void hideNavigator(DevTools devTools) {
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
            "Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 1});\n" +
            "Object.defineProperty(navigator.connection, 'rtt', {get: () => 100});\n" +
            "\n" +
            "      // https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/chrome-runtime.js\n" +
            "      window.chrome = {\n" +
            "          app: {\n" +
            "              isInstalled: false,\n" +
            "              InstallState: {\n" +
            "                  DISABLED: 'disabled',\n" +
            "                  INSTALLED: 'installed',\n" +
            "                  NOT_INSTALLED: 'not_installed'\n" +
            "              },\n" +
            "              RunningState: {\n" +
            "                  CANNOT_RUN: 'cannot_run',\n" +
            "                  READY_TO_RUN: 'ready_to_run',\n" +
            "                  RUNNING: 'running'\n" +
            "              }\n" +
            "          },\n" +
            "          runtime: {\n" +
            "              OnInstalledReason: {\n" +
            "                  CHROME_UPDATE: 'chrome_update',\n" +
            "                  INSTALL: 'install',\n" +
            "                  SHARED_MODULE_UPDATE: 'shared_module_update',\n" +
            "                  UPDATE: 'update'\n" +
            "              },\n" +
            "              OnRestartRequiredReason: {\n" +
            "                  APP_UPDATE: 'app_update',\n" +
            "                  OS_UPDATE: 'os_update',\n" +
            "                  PERIODIC: 'periodic'\n" +
            "              },\n" +
            "              PlatformArch: {\n" +
            "                  ARM: 'arm',\n" +
            "                  ARM64: 'arm64',\n" +
            "                  MIPS: 'mips',\n" +
            "                  MIPS64: 'mips64',\n" +
            "                  X86_32: 'x86-32',\n" +
            "                  X86_64: 'x86-64'\n" +
            "              },\n" +
            "              PlatformNaclArch: {\n" +
            "                  ARM: 'arm',\n" +
            "                  MIPS: 'mips',\n" +
            "                  MIPS64: 'mips64',\n" +
            "                  X86_32: 'x86-32',\n" +
            "                  X86_64: 'x86-64'\n" +
            "              },\n" +
            "              PlatformOs: {\n" +
            "                  ANDROID: 'android',\n" +
            "                  CROS: 'cros',\n" +
            "                  LINUX: 'linux',\n" +
            "                  MAC: 'mac',\n" +
            "                  OPENBSD: 'openbsd',\n" +
            "                  WIN: 'win'\n" +
            "              },\n" +
            "              RequestUpdateCheckStatus: {\n" +
            "                  NO_UPDATE: 'no_update',\n" +
            "                  THROTTLED: 'throttled',\n" +
            "                  UPDATE_AVAILABLE: 'update_available'\n" +
            "              }\n" +
            "          }\n" +
            "      }\n" +
            "\n" +
            "      // https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/navigator-permissions.js\n" +
            "      if (!window.Notification) {\n" +
            "          window.Notification = {\n" +
            "              permission: 'denied'\n" +
            "          }\n" +
            "      }\n" +
            "\n" +
            "      const originalQuery = window.navigator.permissions.query\n" +
            "      window.navigator.permissions.__proto__.query = parameters =>\n" +
            "          parameters.name === 'notifications'\n" +
            "              ? Promise.resolve({ state: window.Notification.permission })\n" +
            "              : originalQuery(parameters)",
            Optional.empty(),
            Optional.empty()
        ));
    }

    /**
     * Overrides call() and toString().
     *
     * Credit for JS goes to https://github.com/ultrafunkamsterdam/undetected-chromedriver/blob/master/undetected_chromedriver/__init__.py
     * with original credit to:
     * - https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/navigator-permissions.js
     * - https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/chrome-runtime.js
     */
    private static void overridePrototypes(DevTools devTools) {
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
            "      const oldCall = Function.prototype.call\n" +
            "      function call() {\n" +
            "          return oldCall.apply(this, arguments)\n" +
            "      }\n" +
            "      Function.prototype.call = call\n" +
            "\n" +
            "      const nativeToStringFunctionString = Error.toString().replace(/Error/g, 'toString')\n" +
            "      const oldToString = Function.prototype.toString\n" +
            "\n" +
            "      function functionToString() {\n" +
            "          if (this === window.navigator.permissions.query) {\n" +
            "              return 'function query() { [native code] }'\n" +
            "          }\n" +
            "          if (this === functionToString) {\n" +
            "              return nativeToStringFunctionString\n" +
            "          }\n" +
            "          return oldCall.call(oldToString, this)\n" +
            "      }\n" +
            "      // eslint-disable-next-line\n" +
            "      Function.prototype.toString = functionToString",
            Optional.empty(),
            Optional.empty()
        ));
    }

    /**
     * Credit goes to https://github.com/ultrafunkamsterdam/undetected-chromedriver/blob/master/undetected_chromedriver/__init__.py
     * @param devTools
     */
    private static void removeCDCProps(DevTools devTools) {
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
            "let objectToInspect = window,\n" +
            "  result = [];\n" +
            "  while(objectToInspect !== null)\n" +
            "  { result = result.concat(Object.getOwnPropertyNames(objectToInspect));\n" +
            "    objectToInspect = Object.getPrototypeOf(objectToInspect); }\n" +
            "  result.forEach(p => p.match(/.+_.+_(Array|Promise|Symbol)/ig)\n" +
            "  && delete window[p]&&console.log('removed',p))",
            Optional.empty(),
            Optional.empty()
        ));
    }

    /**
     * Spoofs navigator.plugins and navigator.mimeTypes. From author:
     *
     * In headless mode `navigator.mimeTypes` and `navigator.plugins` are empty.
     *  This plugin quite emulates both of these to match regular headful Chrome.
     *  We even go so far as to mock functional methods, instance types and `.toString` properties. :D
     *
     *  Credit to https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/navigator-plugins.js/.
     */
    private static void mockPlugins(DevTools devTools) {
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
            "function mockPluginsAndMimeTypes () {\n" +
            "      /* global MimeType MimeTypeArray PluginArray */\n" +
            "\n" +
            "      // Disguise custom functions as being native\n" +
            "      const makeFnsNative = (fns = []) => {\n" +
            "        const oldCall = Function.prototype.call\n" +
            "        function call () {\n" +
            "          return oldCall.apply(this, arguments)\n" +
            "        }\n" +
            "        // eslint-disable-next-line\n" +
            "        Function.prototype.call = call\n" +
            "\n" +
            "        const nativeToStringFunctionString = Error.toString().replace(/Error/g, 'toString')\n" +
            "        const oldToString = Function.prototype.toString\n" +
            "\n" +
            "        function functionToString () {\n" +
            "          for (const fn of fns) {\n" +
            "            if (this === fn.ref) {\n" +
            "              return `function ${fn.name}() { [native code] }`\n" +
            "            }\n" +
            "          }\n" +
            "\n" +
            "          if (this === functionToString) {\n" +
            "            return nativeToStringFunctionString\n" +
            "          }\n" +
            "          return oldCall.call(oldToString, this)\n" +
            "        }\n" +
            "        // eslint-disable-next-line\n" +
            "        Function.prototype.toString = functionToString\n" +
            "      }\n" +
            "\n" +
            "      const mockedFns = []\n" +
            "\n" +
            "      const fakeData = {\n" +
            "        mimeTypes: [\n" +
            "          {\n" +
            "            type: 'application/pdf',\n" +
            "            suffixes: 'pdf',\n" +
            "            description: '',\n" +
            "            __pluginName: 'Chrome PDF Viewer'\n" +
            "          },\n" +
            "          {\n" +
            "            type: 'application/x-google-chrome-pdf',\n" +
            "            suffixes: 'pdf',\n" +
            "            description: 'Portable Document Format',\n" +
            "            __pluginName: 'Chrome PDF Plugin'\n" +
            "          },\n" +
            "          {\n" +
            "            type: 'application/x-nacl',\n" +
            "            suffixes: '',\n" +
            "            description: 'Native Client Executable',\n" +
            "            // eslint-disable-next-line\n" +
            "            enabledPlugin: Plugin,\n" +
            "            __pluginName: 'Native Client'\n" +
            "          },\n" +
            "          {\n" +
            "            type: 'application/x-pnacl',\n" +
            "            suffixes: '',\n" +
            "            description: 'Portable Native Client Executable',\n" +
            "            __pluginName: 'Native Client'\n" +
            "          }\n" +
            "        ],\n" +
            "        plugins: [\n" +
            "          {\n" +
            "            name: 'Chrome PDF Plugin',\n" +
            "            filename: 'internal-pdf-viewer',\n" +
            "            description: 'Portable Document Format'\n" +
            "          },\n" +
            "          {\n" +
            "            name: 'Chrome PDF Viewer',\n" +
            "            filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai',\n" +
            "            description: ''\n" +
            "          },\n" +
            "          {\n" +
            "            name: 'Native Client',\n" +
            "            filename: 'internal-nacl-plugin',\n" +
            "            description: ''\n" +
            "          }\n" +
            "        ],\n" +
            "        fns: {\n" +
            "          namedItem: instanceName => {\n" +
            "            // Returns the Plugin/MimeType with the specified name.\n" +
            "            const fn = function (name) {\n" +
            "              if (!arguments.length) {\n" +
            "                throw new TypeError(\n" +
            "                  `Failed to execute 'namedItem' on '${instanceName}': 1 argument required, but only 0 present.`\n" +
            "                )\n" +
            "              }\n" +
            "              return this[name] || null\n" +
            "            }\n" +
            "            mockedFns.push({ ref: fn, name: 'namedItem' })\n" +
            "            return fn\n" +
            "          },\n" +
            "          item: instanceName => {\n" +
            "            // Returns the Plugin/MimeType at the specified index into the array.\n" +
            "            const fn = function (index) {\n" +
            "              if (!arguments.length) {\n" +
            "                throw new TypeError(\n" +
            "                  `Failed to execute 'namedItem' on '${instanceName}': 1 argument required, but only 0 present.`\n" +
            "                )\n" +
            "              }\n" +
            "              return this[index] || null\n" +
            "            }\n" +
            "            mockedFns.push({ ref: fn, name: 'item' })\n" +
            "            return fn\n" +
            "          },\n" +
            "          refresh: instanceName => {\n" +
            "            // Refreshes all plugins on the current page, optionally reloading documents.\n" +
            "            const fn = function () {\n" +
            "              return undefined\n" +
            "            }\n" +
            "            mockedFns.push({ ref: fn, name: 'refresh' })\n" +
            "            return fn\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "      // Poor mans _.pluck\n" +
            "      const getSubset = (keys, obj) => keys.reduce((a, c) => ({ ...a, [c]: obj[c] }), {})\n" +
            "\n" +
            "      function generateMimeTypeArray () {\n" +
            "        const arr = fakeData.mimeTypes\n" +
            "          .map(obj => getSubset(['type', 'suffixes', 'description'], obj))\n" +
            "          .map(obj => Object.setPrototypeOf(obj, MimeType.prototype))\n" +
            "        arr.forEach(obj => {\n" +
            "          arr[obj.type] = obj\n" +
            "        })\n" +
            "\n" +
            "        // Mock functions\n" +
            "        arr.namedItem = fakeData.fns.namedItem('MimeTypeArray')\n" +
            "        arr.item = fakeData.fns.item('MimeTypeArray')\n" +
            "\n" +
            "        return Object.setPrototypeOf(arr, MimeTypeArray.prototype)\n" +
            "      }\n" +
            "\n" +
            "      const mimeTypeArray = generateMimeTypeArray()\n" +
            "      Object.defineProperty(navigator, 'mimeTypes', {\n" +
            "        get: () => mimeTypeArray\n" +
            "      })\n" +
            "\n" +
            "      function generatePluginArray () {\n" +
            "        const arr = fakeData.plugins\n" +
            "          .map(obj => getSubset(['name', 'filename', 'description'], obj))\n" +
            "          .map(obj => {\n" +
            "            const mimes = fakeData.mimeTypes.filter(m => m.__pluginName === obj.name)\n" +
            "            // Add mimetypes\n" +
            "            mimes.forEach((mime, index) => {\n" +
            "              navigator.mimeTypes[mime.type].enabledPlugin = obj\n" +
            "              obj[mime.type] = navigator.mimeTypes[mime.type]\n" +
            "              obj[index] = navigator.mimeTypes[mime.type]\n" +
            "            })\n" +
            "            obj.length = mimes.length\n" +
            "            return obj\n" +
            "          })\n" +
            "          .map(obj => {\n" +
            "            // Mock functions\n" +
            "            obj.namedItem = fakeData.fns.namedItem('Plugin')\n" +
            "            obj.item = fakeData.fns.item('Plugin')\n" +
            "            return obj\n" +
            "          })\n" +
            "          // eslint-disable-next-line\n" +
            "          .map(obj => Object.setPrototypeOf(obj, Plugin.prototype))\n" +
            "        arr.forEach(obj => {\n" +
            "          arr[obj.name] = obj\n" +
            "        })\n" +
            "\n" +
            "        // Mock functions\n" +
            "        arr.namedItem = fakeData.fns.namedItem('PluginArray')\n" +
            "        arr.item = fakeData.fns.item('PluginArray')\n" +
            "        arr.refresh = fakeData.fns.refresh('PluginArray')\n" +
            "\n" +
            "        return Object.setPrototypeOf(arr, PluginArray.prototype)\n" +
            "      }\n" +
            "\n" +
            "      const pluginArray = generatePluginArray()\n" +
            "      Object.defineProperty(navigator, 'plugins', {\n" +
            "        get: () => pluginArray\n" +
            "      })\n" +
            "\n" +
            "      // Make mockedFns toString() representation resemble a native function\n" +
            "      makeFnsNative(mockedFns)\n" +
            "    }\n" +
            "    try {\n" +
            "      const isPluginArray = navigator.plugins instanceof PluginArray\n" +
            "      const hasPlugins = isPluginArray && navigator.plugins.length > 0\n" +
            "      if (!isPluginArray || !hasPlugins) {\n" +
            "        mockPluginsAndMimeTypes()\n" +
            "      }\n" +
            "    } catch (err) {}\n",
            Optional.empty(),
            Optional.empty()
        ));
    }

    /**
     * Mock window.outerWidth and window.outerHeight.
     *
     * Credit: https://github.com/microlinkhq/browserless/blob/master/packages/goto/src/evasions/window-frame.js
     */
    private static void mockWindowFrame(DevTools devTools) {
        devTools.send(Page.addScriptToEvaluateOnNewDocument(
            "if (!window.outerWidth) {\n" +
            "    window.outerWidth = window.innerWidth\n" +
            "}\n" +
            "if (!window.outerHeight){\n" +
            "    const windowFrame = 74\n" +
            "    window.outerHeight = window.innerHeight + windowFrame\n" +
            "}\n",
            Optional.empty(),
            Optional.empty()
        ));
    }

    /***
     *
     * Override user agent to the user agent that is configured with lib-http
     *
     */
    private static void overrideUserAgent(DevTools devTools, String userAgent) {
        devTools.send(Network.setUserAgentOverride(userAgent, Optional.empty(), Optional.empty(), Optional.empty()));
    }
}