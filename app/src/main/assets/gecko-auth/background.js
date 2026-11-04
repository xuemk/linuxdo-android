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
    default:
      throw new Error("Unknown authentication cookie command");
  }
});
