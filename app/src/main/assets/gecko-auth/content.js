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

  // 在扩展隔离环境里读 DOM，不受页面 CSP 对 eval 的限制；标记词由 Native 传入，与 WebView 探测脚本同源。
  function matchMarkers(groups) {
    let html = "";
    try { html = ((document.documentElement && document.documentElement.innerHTML) || "") + " " + (document.title || ""); } catch (e) {}
    html = html.toLowerCase();
    const result = {};
    for (const [name, words] of Object.entries(groups || {})) {
      result[name] = (words || []).some(word => html.indexOf(String(word).toLowerCase()) !== -1);
    }
    return result;
  }

  installPageBridge();
  port.onMessage.addListener(async request => {
    try {
      let value;
      if (request.command === "evaluate") {
        value = page.eval(String(request.script));
        // document.write 替换认证文档后重新安装桥，沿用 master 的原始内联页面。
        installPageBridge();
      } else if (request.command === "markers") {
        value = matchMarkers(request.groups);
      } else if (request.command === "trace") {
        const response = await fetch("https://linux.do/cdn-cgi/trace", { credentials: "include", cache: "no-store" });
        value = (await response.text()).slice(0, 2048);
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
