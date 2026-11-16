"use strict";

const FORUM_ORIGIN = "https://linux.do";
const isolatedAuthTabs = new Map();

function isForumUrl(value) {
  try {
    const url = new URL(value);
    return url.origin === FORUM_ORIGIN && !url.username && !url.password;
  } catch (error) { return false; }
}

function isAuthorizationRequest(details) {
  const sameOriginDocument = details.frameId === 0 && isForumUrl(details.documentUrl || details.originUrl);
  const topNavigation = details.frameId === 0 && details.type === "main_frame" &&
    (details.method === "GET" || details.method === "HEAD");
  return isForumUrl(details.url) && (sameOriginDocument || topNavigation);
}

function validCookieEntries(incoming) {
  if (!incoming || typeof incoming !== "object" || Array.isArray(incoming)) return [];
  return Object.entries(incoming || {}).filter(([name, value]) =>
    /^[!#$%&'*+\-.^_\x60|~0-9a-zA-Z]+$/.test(name) &&
    typeof value === "string" && value.length > 0 && !/[\x00-\x20\x7f;,]/.test(value));
}

// contextId 分区不由 cookies.storeId 表示；只在对应 tab 的主站请求头中单向提供授权。
browser.webRequest.onBeforeSendHeaders.addListener(details => {
  const authorization = isolatedAuthTabs.get(details.tabId);
  // 只供主站顶层授权及其同源请求，外站 XHR、iframe 和跨站 POST 不能借用登录态。
  if (!authorization || !isAuthorizationRequest(details)) return {};
  const headers = details.requestHeaders || [];
  const existing = headers.filter(header => header.name.toLowerCase() === "cookie")
    .flatMap(header => (header.value || "").split(";")).map(entry => {
      const separator = entry.indexOf("=");
      return separator < 1 ? null : [entry.slice(0, separator).trim(), entry.slice(separator + 1).trim()];
    }).filter(Boolean);
  const incoming = authorization.cookies;
  const cookies = existing.filter(([name, value]) =>
    !(name === "_t" && incoming.has("_t")) &&
    !(name === "cf_clearance" && authorization.challenged.has(value)));
  for (const [name, value] of incoming) {
    if (name === "cf_clearance") {
      if (authorization.challenged.has(value) || cookies.some(([key]) => key === name)) continue;
    } else if (/^(cf_|__?cf)/i.test(name)) {
      continue;
    } else if (name !== "_t" && cookies.some(([key]) => key === name)) {
      continue; // 保留服务端更新的 _forum_session，避免覆盖 OAuth 状态和 CSRF 会话。
    }
    cookies.push([name, value]);
  }
  const result = headers.filter(header => header.name.toLowerCase() !== "cookie");
  if (cookies.length) result.push({ name: "Cookie", value: cookies.map(([name, value]) => name + "=" + value).join("; ") });
  return { requestHeaders: result };
}, { urls: [FORUM_ORIGIN + "/*"] }, ["blocking", "requestHeaders"]);

// 只接纳主站响应签发的令牌更新，不能把 H5 脚本写入的同名 Cookie 当成授权凭据。
browser.webRequest.onHeadersReceived.addListener(details => {
  const authorization = isolatedAuthTabs.get(details.tabId);
  if (!authorization || !isAuthorizationRequest(details)) return;
  for (const header of details.responseHeaders || []) {
    if (header.name.toLowerCase() !== "set-cookie") continue;
    const pair = (header.value || "").split(";", 1)[0];
    const separator = pair.indexOf("=");
    if (pair.slice(0, separator).trim() !== "_t") continue;
    const value = pair.slice(separator + 1).trim();
    if (!value) authorization.cookies.delete("_t");
    else if (validCookieEntries({ _t: value }).length) authorization.cookies.set("_t", value);
  }
}, { urls: [FORUM_ORIGIN + "/*"] }, ["responseHeaders"]);

browser.tabs.onRemoved.addListener(tabId => isolatedAuthTabs.delete(tabId));

async function cookiesInStore(store) {
  const scope = { storeId: store };
  const unpartitioned = await browser.cookies.getAll({ domain: "linux.do", ...scope });
  let partitioned = [];
  try {
    // 使用完整 originAttributes 的准确分区；partitionKey:{} 会连同 H5 contextId 一起遍历。
    partitioned = await browser.cookies.getAll({
      domain: "linux.do", url: FORUM_ORIGIN + "/",
      partitionKey: { topLevelSite: FORUM_ORIGIN }, ...scope
    });
  } catch (error) {}
  const seen = new Set();
  return unpartitioned.concat(partitioned).filter(cookie => {
    if (cookie.storeId !== store || cookie.domain.replace(/^\./, "") !== "linux.do") return false;
    const key = JSON.stringify([cookie.name, cookie.value, cookie.domain, cookie.path, cookie.storeId, cookie.partitionKey || null]);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function removeCookie(cookie, store) {
  return browser.cookies.remove({
    url: FORUM_ORIGIN + cookie.path, name: cookie.name, storeId: store,
    ...(cookie.partitionKey ? { partitionKey: cookie.partitionKey } : {})
  });
}

// 背景原生端口无需打开网页，也不会用全站清理误删持久 H5 容器。
let cookieControlPort = null;
let cookieControlRetry = null;
let cookieControlAttempts = 0;
function connectCookieControl() {
  if (cookieControlPort || cookieControlRetry) return;
  const retry = () => {
    if (cookieControlAttempts++ >= 30) return;
    cookieControlRetry = setTimeout(() => {
      cookieControlRetry = null;
      connectCookieControl();
    }, 100);
  };
  try {
    const port = browser.runtime.connectNative("linuxdoCookieControl");
    cookieControlPort = port;
    port.onMessage.addListener(async request => {
      cookieControlAttempts = 0;
      try {
        if (request.command !== "clearSiteCookies") throw new Error("Unsupported cookie control command");
        const cookies = await cookiesInStore("firefox-default");
        for (const cookie of cookies) await removeCookie(cookie, "firefox-default");
        port.postMessage({ id: request.id, value: true });
      } catch (error) {
        port.postMessage({ id: request.id, error: String(error.message || error), value: false });
      }
    });
    port.onDisconnect.addListener(() => {
      if (cookieControlPort !== port) return;
      cookieControlPort = null;
      retry();
    });
  } catch (error) { retry(); }
}
connectCookieControl();

browser.runtime.onMessage.addListener(async (request, sender) => {
  if (sender.id !== browser.runtime.id || new URL(sender.url).origin !== FORUM_ORIGIN) {
    throw new Error("Unsupported authentication origin");
  }
  const tabId = sender.tab && sender.tab.id;
  if (request.command === "prepareIsolatedInAppSession") {
    if (!Number.isInteger(tabId) || tabId < 0 || typeof request.contextId !== "string" ||
        !/^linuxdo-h5-[a-z0-9-]+-[0-9]+$/.test(request.contextId)) {
      throw new Error("Unsupported isolated authentication context");
    }
    isolatedAuthTabs.set(tabId, {
      cookies: new Map(validCookieEntries(request.cookies)),
      challenged: new Set(Array.isArray(request.challengedClearances) ? request.challengedClearances : [])
    });
    return true;
  }
  if (isolatedAuthTabs.has(tabId)) throw new Error("Isolated H5 cannot read or clear main-site cookies");
  const store = sender.tab && sender.tab.cookieStoreId;
  if (!store) throw new Error("Authentication cookie store is unavailable");
  const scope = { storeId: store };
  const cookies = await cookiesInStore(store);
  const remove = cookie => removeCookie(cookie, store);
  switch (request.command) {
    case "cookies":
      return cookies;
    case "clearCf":
      for (const cookie of cookies.filter(cookie => cookie.name === "cf_clearance")) await remove(cookie);
      return true;
    case "prepareAuth": {
      // 主站登录只重建默认存储内的账号会话。
      const authNames = new Set(["_t", "_forum_session", "h_captcha_temp_id"]);
      for (const cookie of cookies.filter(cookie => authNames.has(cookie.name))) await remove(cookie);
      const existing = new Set(cookies.map(cookie => cookie.name));
      for (const [name, value] of validCookieEntries(request.cookies)) {
        if (authNames.has(name) || existing.has(name) || /^(cf_|__?cf)/i.test(name)) continue;
        await browser.cookies.set({ url: FORUM_ORIGIN + "/", name, value, path: "/", secure: true, ...scope });
      }
      return true;
    }
    case "prepareInAppSession": {
      const challenged = new Set(Array.isArray(request.challengedClearances) ? request.challengedClearances : []);
      let hasValidCf = false;
      for (const cookie of cookies) {
        if (cookie.name === "cf_clearance") {
          if (challenged.has(cookie.value)) {
            try { await remove(cookie); } catch (error) {}
          } else if (cookie.value) hasValidCf = true;
        }
      }
      for (const [name, value] of validCookieEntries(request.cookies)) {
        if (name === "cf_clearance") {
          if (!hasValidCf && !challenged.has(value)) {
            try {
              await browser.cookies.set({
                url: FORUM_ORIGIN + "/", domain: ".linux.do", name, value,
                path: "/", secure: true, httpOnly: true, ...scope
              });
              hasValidCf = true;
            } catch (error) {}
          }
          continue;
        }
        if (/^(cf_|__?cf)/i.test(name)) continue;
        if (cookies.some(cookie => cookie.name === name && cookie.value === value)) continue;
        for (const old of cookies.filter(cookie => cookie.name === name)) {
          try { await remove(old); } catch (error) {}
        }
        try {
          await browser.cookies.set({
            url: FORUM_ORIGIN + "/", name, value, path: "/", secure: true,
            httpOnly: name === "_t" || name === "_forum_session", ...scope
          });
        } catch (error) {}
      }
      return true;
    }
    default:
      throw new Error("Unknown authentication cookie command");
  }
});
