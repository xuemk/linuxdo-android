"use strict";

browser.runtime.onMessage.addListener(async (request, sender) => {
  if (sender.id !== browser.runtime.id || new URL(sender.url).origin !== "https://linux.do") {
    throw new Error("Unsupported authentication origin");
  }
  const store = sender.tab && sender.tab.cookieStoreId;
  const scope = store ? { storeId: store } : {};
  const storeIds = new Set([undefined]);
  if (store) storeIds.add(store);
  try {
    const stores = await browser.cookies.getAllCookieStores();
    for (const item of stores || []) {
      if (item && item.id) storeIds.add(item.id);
    }
  } catch (e) {}
  let combined = [];
  for (const storeId of storeIds) {
    const storeScope = storeId ? { storeId } : {};
    try {
      const unpartitioned = await browser.cookies.getAll({ domain: "linux.do", ...storeScope });
      combined = combined.concat(unpartitioned);
    } catch (e) {}
    try {
      const partitioned = await browser.cookies.getAll({ domain: "linux.do", partitionKey: {}, ...storeScope });
      combined = combined.concat(partitioned);
    } catch (e) {}
  }
  const seen = new Set();
  const cookies = combined.filter(cookie => {
    if (cookie.domain.replace(/^\./, "") !== "linux.do") return false;
    const key = `${cookie.name}|${cookie.value || ""}|${cookie.domain}|${cookie.path}|${cookie.storeId || ""}|${JSON.stringify(cookie.partitionKey || null)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const remove = cookie => browser.cookies.remove({
    url: "https://linux.do" + cookie.path,
    name: cookie.name,
    ...(cookie.partitionKey ? { partitionKey: cookie.partitionKey } : {}),
    ...(cookie.storeId ? { storeId: cookie.storeId } : scope)
  });
  switch (request.command) {
    case "cookies":
      return cookies;
    case "clearCf":
      for (const cookie of cookies.filter(cookie => cookie.name === "cf_clearance")) await remove(cookie);
      return true;
    case "prepareAuth": {
      // 新一轮登录不复用上一账号的会话；CF 凭据和当前浏览器中的其他 Cookie 保持有效。
      const authNames = new Set(["_t", "_forum_session", "h_captcha_temp_id"]);
      for (const cookie of cookies.filter(cookie => authNames.has(cookie.name))) await remove(cookie);
      const existing = new Set(cookies.map(cookie => cookie.name));
      for (const [name, value] of Object.entries(request.cookies || {})) {
        if (authNames.has(name) || existing.has(name) || /^(cf_|__?cf)/i.test(name)) continue;
        await browser.cookies.set({ url: "https://linux.do/", name, value, path: "/", secure: true, ...scope });
      }
      return true;
    }
    case "prepareInAppSession": {
      // 内置同域网页/嵌入应用：剔除已判废的旧 cf_clearance，保留有效 cf_clearance，并同步当前账号的登录 Cookie。
      const challenged = new Set(Array.isArray(request.challengedClearances) ? request.challengedClearances : []);
      let hasValidCf = false;
      for (const cookie of cookies) {
        if (cookie.name === "cf_clearance") {
          if (challenged.has(cookie.value)) {
            try { await remove(cookie); } catch (e) {}
          } else if (cookie.value) {
            hasValidCf = true;
          }
        }
      }
      const incoming = request.cookies || {};
      for (const [name, value] of Object.entries(incoming)) {
        if (!value) continue;
        if (name === "cf_clearance") {
          if (!hasValidCf && !challenged.has(value)) {
            try {
              await browser.cookies.set({
                url: "https://linux.do/",
                domain: ".linux.do",
                name: "cf_clearance",
                value: String(value),
                path: "/",
                secure: true,
                httpOnly: true,
                ...scope
              });
              hasValidCf = true;
            } catch (e) {}
          }
          continue;
        }
        if (/^(cf_|__?cf)/i.test(name)) continue;
        const existingSame = cookies.some(c => c.name === name && c.value === value);
        if (existingSame) continue;
        for (const old of cookies.filter(c => c.name === name)) {
          try { await remove(old); } catch (e) {}
        }
        try {
          await browser.cookies.set({
            url: "https://linux.do/",
            name,
            value: String(value),
            path: "/",
            secure: true,
            httpOnly: name === "_t" || name === "_forum_session",
            ...scope
          });
        } catch (e) {}
      }
      return true;
    }
    default:
      throw new Error("Unknown authentication cookie command");
  }
});
