// Aurabrowse DevTools bridge content script.
// Runs in every page at document_start and opens a port to the native app.
// Handles {type:"eval", code:"..."} messages and posts back
// {type:"evalResult", ok:true, result:"..."} or {type:"evalResult", ok:false, error:"..."}.
(function () {
  "use strict";

  var port = null;
  try {
    port = browser.runtime.connect({ name: "aurabrowse-devtools" });
  } catch (e) {
    return;
  }

  function stringify(v) {
    if (v === undefined) return "undefined";
    if (v === null) return "null";
    if (typeof v === "function") return v.toString();
    try {
      return JSON.stringify(v, null, 2);
    } catch (e) {
      return String(v);
    }
  }

  function respond(msg) {
    try {
      port.postMessage(msg);
    } catch (e) {
      // Port may be gone; ignore.
    }
  }

  port.onMessage.addListener(function (msg) {
    if (!msg || msg.type !== "eval") return;
    var target = msg.target || "console";
    try {
      var result = eval(msg.code);
      if (result && typeof result.then === "function") {
        result.then(
          function (v) {
            respond({ type: "evalResult", target: target, ok: true, result: stringify(v) });
          },
          function (e) {
            respond({ type: "evalResult", target: target, ok: false, error: String(e) });
          }
        );
      } else {
        respond({ type: "evalResult", target: target, ok: true, result: stringify(result) });
      }
    } catch (e) {
      respond({ type: "evalResult", target: target, ok: false, error: String(e) });
    }
  });

  try {
    port.postMessage({ type: "ready" });
  } catch (e) {
    // ignore
  }
})();
