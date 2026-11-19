"use strict";

(() => {
  const isMainForumOrigin = location.origin === "https://linux.do";
  const isWebmailOrigin = location.origin === "https://webmail.linux.do";
  if (window.top !== window || (!isMainForumOrigin && !location.hostname.endsWith(".linux.do"))) return;
  // 初始化纯文本不参与展示；只遮盖 pre，真实的安全验证表单仍可见。
  if (isMainForumOrigin && location.pathname === "/robots.txt") {
    const hideBootstrapText = () => {
      if (!document.documentElement) return false;
      if (!document.getElementById("linuxdo-bootstrap-cover")) {
        const style = document.createElement("style");
        style.id = "linuxdo-bootstrap-cover";
        style.textContent = "body > pre { visibility: hidden !important; }";
        document.documentElement.append(style);
      }
      return true;
    };
    if (!hideBootstrapText()) {
      const observer = new MutationObserver(() => {
        if (hideBootstrapText()) observer.disconnect();
      });
      observer.observe(document, { childList: true, subtree: true });
    }
  }
  const port = browser.runtime.connectNative("linuxdoAuth");
  const page = window.wrappedJSObject;
  const callbackNames = [
    "onHcaptchaPass", "onAuthResult", "onAuthAborted", "onDiagnostic",
    "onChallengeOriginReached", "onChallengeNavigating"
  ];

  function installPageBridge() {
    if (!isMainForumOrigin) return;
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

  let inAppObserver;
  let lastInAppState = "";
  let miniAppLoader;
  let miniAppAncestors = [];
  let observationStartedAt = 0;
  const inAppDocumentId = `${Date.now()}-${Math.random()}`;
  const sizedFrames = new WeakSet();

  function presentMiniApp(loader) {
    if (!document.getElementById("linuxdo-mini-app-presentation")) {
      const style = document.createElement("style");
      style.id = "linuxdo-mini-app-presentation";
      style.textContent = `
        html[data-linuxdo-mini-app],html[data-linuxdo-mini-app] body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important}
        html[data-linuxdo-mini-app] .linuxdo-mini-app-path{display:block!important;position:static!important;transform:none!important;contain:none!important;width:100%!important;height:100%!important;min-height:0!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important}
        html[data-linuxdo-mini-app] .linuxdo-mini-app-path>:not(.linuxdo-mini-app-path):not(.mini-app-loader):not(dialog):not([role="dialog"]):not(.modal-container){display:none!important}
        html[data-linuxdo-mini-app] .mini-app-loader{position:fixed!important;inset:0!important;width:100%!important;height:100%!important;min-height:0!important;max-height:none!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important}
        html[data-linuxdo-mini-app] .mini-app-loader__viewport,html[data-linuxdo-mini-app] .mini-app-loader__frame,html[data-linuxdo-mini-app] .mini-app-frame-host{width:100%!important;height:100%!important;min-height:0!important;max-height:none!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important}
      `;
      document.documentElement.append(style);
    }
    if (miniAppLoader !== loader) {
      for (const element of miniAppAncestors) element.classList.remove("linuxdo-mini-app-path");
      miniAppAncestors = [];
      miniAppLoader = loader;
    }
    for (let element = loader.parentElement; element && element !== document.documentElement; element = element.parentElement) {
      if (!element.classList.contains("linuxdo-mini-app-path")) element.classList.add("linuxdo-mini-app-path");
      if (!miniAppAncestors.includes(element)) miniAppAncestors.push(element);
    }
    document.documentElement.setAttribute("data-linuxdo-mini-app", "");
    for (const frame of loader.querySelectorAll(".mini-app-frame-host")) {
      const sizeFrame = () => {
        try {
          const doc = frame.contentDocument;
          if (!doc || doc.location.origin !== location.origin || doc.location.pathname !== "/mini-app-api/frame-host") return;
          // Only size the official host; the sandboxed application keeps its own document and permissions.
          for (const element of [doc.documentElement, doc.body, ...doc.querySelectorAll("body>iframe")]) {
            if (!element) continue;
            for (const [property, value] of Object.entries({ width: "100%", height: "100%", margin: "0", padding: "0",
              display: "block", "min-height": "0", "max-height": "none", border: "0" })) element.style.setProperty(property, value, "important");
          }
        } catch (_) {}
      };
      sizeFrame();
      if (!sizedFrames.has(frame)) { sizedFrames.add(frame); frame.addEventListener("load", sizeFrame); }
    }
  }

  let webmailCaptureInstalled = false;
  let suppressWebmailCapture = false;

  function isWebmailLoginPath(pathname = location.pathname) {
    return /^\/(sso\/login|admin\/ui\/login|login\b|$)/i.test(pathname || "");
  }

  function isWebmailLogoutUrl(urlStr = location.href) {
    return /[?&](?:_task|action)=logout\b|\/(?:sso\/)?logout\b/i.test(urlStr || "");
  }

  function isWebmailLogoutTarget(target) {
    const el = target?.closest?.('a, button, input[type="button"], input[type="submit"], [role="button"], [role="menuitem"]');
    if (!el) return false;
    const sig = [
      el.getAttribute?.("href"),
      el.getAttribute?.("data-command"),
      el.getAttribute?.("onclick"),
      el.id,
      el.name,
      el.className,
      el.getAttribute?.("aria-label"),
      el.value,
      el.textContent,
    ].filter(Boolean).join(" ");
    return /\blogout\b|\bsign[-_ ]?out\b|退出登录|注销/i.test(sig);
  }

  function findWebmailLoginElements() {
    if (!isWebmailOrigin) return null;
    const passInputs = Array.from(document.querySelectorAll('input[type="password"]:not([disabled])'));
    // 严格要求单密码框，排除邮箱后台修改密码页（含原密码/新密码/确认密码多个密码框）
    if (passInputs.length !== 1) return null;
    const passInput = passInputs[0];
    const form = passInput.closest("form") || document;
    const loginPath = isWebmailLoginPath();
    const userInput = form.querySelector(
      'input[name="address"], input[type="email"], input[name="email"], input[name="username"], input[name="user"], input[name="_user"]'
    ) || (loginPath ? form.querySelector('input[type="text"]:not([readonly]):not([disabled])') : null);
    if (!userInput) return null;
    const candidates = Array.from(form.querySelectorAll('input[type="submit"], button[type="submit"], button:not([type])'));
    const webmailBtn = candidates.find(el =>
      el.name === "submitWebmail" ||
      el.id === "submitWebmail" ||
      /webmail/i.test((el.value || "") + " " + (el.textContent || ""))
    );
    const fallbackBtn = loginPath ? candidates.find(el =>
      !/admin/i.test((el.name || "") + " " + (el.id || "") + " " + (el.value || "") + " " + (el.textContent || ""))
    ) : null;
    const submitBtn = webmailBtn || fallbackBtn || null;
    if (!submitBtn && !loginPath) return null;
    const hasError = !!document.querySelector(
      '.alert-danger, .alert-warning, .alert-error, .toast-error, .invalid-feedback, .has-error, .error-message, [role="alert"].alert'
    );
    return { form, userInput, passInput, submitBtn, hasError };
  }

  function fillInputValue(input, value) {
    if (!input || typeof value !== "string") return;
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    if (setter) setter.call(input, value);
    else input.value = value;
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function installWebmailCapture() {
    if (!isWebmailOrigin || webmailCaptureInstalled) return;
    webmailCaptureInstalled = true;
    const reportIfValid = () => {
      if (suppressWebmailCapture) return;
      const els = findWebmailLoginElements();
      if (!els) return;
      const address = (els.userInput.value || "").trim();
      const password = els.passInput.value || "";
      if (address && password) {
        port.postMessage({
          type: "callback",
          name: "onWebmailCredentials",
          payload: JSON.stringify({ address, password })
        });
      }
    };
    document.addEventListener("submit", reportIfValid, true);
    document.addEventListener("click", event => {
      const target = event.target?.closest?.('input[type="submit"], button[type="submit"], button:not([type])');
      if (target) reportIfValid();
      if (!isWebmailLoginPath() && isWebmailLogoutTarget(event.target)) {
        port.postMessage({ type: "callback", name: "onWebmailLogout", payload: "" });
      }
    }, true);
  }

  function inAppState() {
    const appId = location.pathname.match(/^\/apps\/([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\/?$/)?.[1];
    const challenge = !!document.querySelector("#challenge-running,#challenge-stage,form#challenge-form,.cf-turnstile") ||
      /^(just a moment|checking your browser|verifying you are human|请稍候|请稍等|正在验证)/i.test(document.title.trim());
    const bootstrap = location.pathname === "/robots.txt";
    const loader = appId && document.querySelector(".mini-app-page .mini-app-loader");
    if (loader && new URL(location.href).searchParams.get("embed_mode") === "true") presentMiniApp(loader);
    if (!appId) {
      document.documentElement?.removeAttribute("data-linuxdo-mini-app");
      for (const element of miniAppAncestors) element.classList.remove("linuxdo-mini-app-path");
      miniAppAncestors = []; miniAppLoader = undefined;
    }
    if (isWebmailOrigin) installWebmailCapture();
    const loading = loader?.querySelector(".mini-app-loader__loading");
    const permission = !!document.querySelector(".mini-app-permission-dialog[open]");
    const phase = challenge ? "challenge" : bootstrap ? "bootstrap" : permission ? "permission" :
      loader?.classList.contains("is-error") ? "error" :
      loader ? (loader.classList.contains("is-loading") || loading && !loading.hidden ? "loading" : "ready") :
      appId ? (document.readyState === "complete" && Date.now() - observationStartedAt >= 2000 ? "unavailable" : "loading") :
      document.readyState === "loading" ? "loading" : "ready";
    const webmailEls = !challenge && isWebmailOrigin ? findWebmailLoginElements() : null;
    const webmailLogout = !challenge && isWebmailOrigin && isWebmailLogoutUrl();
    const webmailAuthenticated = !challenge && isWebmailOrigin && !webmailEls &&
      !isWebmailLoginPath() && !webmailLogout && document.readyState === "complete";
    return { url: location.href, documentId: inAppDocumentId, phase,
      webmailLogin: !!webmailEls, webmailLoginError: !!webmailEls?.hasError,
      webmailAuthenticated, webmailLogout,
      detail: phase === "error" ? loader.querySelector('[data-role="detail"]')?.textContent.trim().slice(0, 300) || "小程序启动失败" : "" };
  }

  function observeInAppPage() {
    const publish = () => {
      const state = inAppState();
      const encoded = JSON.stringify(state);
      if (encoded !== lastInAppState) {
        lastInAppState = encoded;
        port.postMessage({ type: "callback", name: "onInAppPageState", payload: encoded });
      }
      return state;
    };
    if (!inAppObserver) {
      observationStartedAt = Date.now();
      inAppObserver = new MutationObserver(publish);
      inAppObserver.observe(document, { childList: true, subtree: true, characterData: true,
        attributes: true, attributeFilter: ["class", "hidden", "open", "disabled"] });
      addEventListener("load", publish, { once: true });
      setTimeout(publish, 2100);
    }
    return publish();
  }

  installPageBridge();
  port.onMessage.addListener(async request => {
    if (request && request.command === "disconnect") {
      try { port.disconnect(); } catch (_) {}
      return;
    }
    try {
      let value;
      if (request.command === "evaluate") {
        value = page.eval(String(request.script));
        // document.write 替换认证文档后重新安装桥，沿用 master 的原始内联页面。
        if (isMainForumOrigin) installPageBridge();
      } else if (request.command === "markers") {
        value = matchMarkers(request.groups);
      } else if (request.command === "observeInAppPage") {
        value = observeInAppPage();
      } else if (request.command === "fillWebmailLogin") {
        const els = findWebmailLoginElements();
        if (!els) {
          value = { filled: false, submitted: false };
        } else {
          fillInputValue(els.userInput, String(request.address || ""));
          fillInputValue(els.passInput, String(request.password || ""));
          const shouldSubmit = !!request.autoSubmit && !els.hasError && !!els.submitBtn;
          if (shouldSubmit) {
            setTimeout(() => {
              suppressWebmailCapture = true;
              try { els.submitBtn.click(); } catch (_) {}
              setTimeout(() => { suppressWebmailCapture = false; }, 100);
            }, 50);
          }
          value = { filled: true, submitted: shouldSubmit };
        }
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

  const ready = () => port.postMessage({ type: "ready", url: location.href });
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", ready, { once: true });
  } else {
    ready();
  }
})();
