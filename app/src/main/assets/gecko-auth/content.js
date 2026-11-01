"use strict";

(() => {
  if (window.top !== window || location.origin !== "https://linux.do") return;
  const port = browser.runtime.connectNative("linuxdoAuth");
  const page = window.wrappedJSObject;
  const callbackNames = [
    "onHcaptchaPass", "onAuthResult", "onAuthAborted", "onDiagnostic",
    "onChallengeOriginReached", "onChallengeNavigating"
  ];

  function installPageBridge() {
    const callbacks = {};
    for (const name of callbackNames) {
      callbacks[name] = payload => port.postMessage({ type: "callback", name, payload: String(payload || "") });
    }
    Object.defineProperty(page, "__ldb", {
      value: cloneInto(callbacks, page, { cloneFunctions: true }),
      configurable: true
    });
  }

  installPageBridge();
  port.onMessage.addListener(async request => {
    try {
      let value;
      if (request.command === "evaluate") {
        value = page.eval(String(request.script));
        // document.write 替换认证文档后重新安装桥，沿用 master 的原始内联页面。
        installPageBridge();
      } else {
        value = await browser.runtime.sendMessage(request);
      }
      port.postMessage({ type: "response", id: request.id, value: JSON.stringify(value ?? null) });
    } catch (error) {
      port.postMessage({ type: "response", id: request.id, error: String(error.message || error) });
    }
  });

  const ready = () => port.postMessage({ type: "ready" });
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", ready, { once: true });
  } else {
    ready();
  }
})();
