// Aurabrowse DevTools bridge background script.
// Captures every network request via browser.webRequest (the only GeckoView API that
// exposes real request headers, method, type and status) and forwards each event to the
// native app over the "network" port — registered by DevToolsBridge with
// registerBackgroundMessageHandler. Request and response events share a requestId so the
// app can merge them into a single log entry.
(function () {
  "use strict";

  var port = null;

  function connect() {
    try {
      port = browser.runtime.connectNative("network");
    } catch (e) {
      port = null;
    }
    if (port) {
      try {
        port.onDisconnect.addListener(function () {
          port = null;
          setTimeout(connect, 1000);
        });
      } catch (e) {}
    }
  }

  function send(msg) {
    if (port) {
      try {
        port.postMessage(msg);
      } catch (e) {
        // Port may be gone; ignore.
      }
    }
  }

  // The app registers the "network" delegate when the extension install callback fires,
  // which can be after this script starts — retry briefly until the port is established.
  connect();
  var attempts = 0;
  var retry = setInterval(function () {
    if (port || ++attempts >= 10) {
      clearInterval(retry);
      return;
    }
    connect();
  }, 250);

  browser.webRequest.onBeforeSendHeaders.addListener(
    function (details) {
      send({
        type: "network",
        event: "request",
        url: details.url,
        method: details.method,
        requestId: String(details.requestId),
        requestType: details.type || "",
        requestHeaders: details.requestHeaders || []
      });
    },
    { urls: ["<all_urls>"] },
    ["requestHeaders"]
  );

  browser.webRequest.onHeadersReceived.addListener(
    function (details) {
      send({
        type: "network",
        event: "response",
        url: details.url,
        method: details.method,
        requestId: String(details.requestId),
        requestType: details.type || "",
        statusCode: details.statusCode,
        responseHeaders: details.responseHeaders || []
      });
    },
    { urls: ["<all_urls>"] },
    ["responseHeaders"]
  );
})();
