// Antigravity AdBlock - Background Service Worker (Manifest V3)
const DEFAULT_SUBSCRIPTIONS = [
  {
    id: "sub_antigravity_default",
    name: "Antigravity Core Blocklist",
    url: "https://raw.githubusercontent.com/axelbrux99-cyber/antigravity-adblock-android/main/app/src/main/assets/blocklist.txt",
    enabled: true,
    count: 209,
    lastUpdated: Date.now()
  },
  {
    id: "sub_peter_lowe",
    name: "Peter Lowe's Ad & Tracker List",
    url: "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    enabled: true,
    count: 0,
    lastUpdated: null
  },
  {
    id: "sub_adguard_base",
    name: "AdGuard Mobile & Tracking Filter",
    url: "https://raw.githubusercontent.com/AdguardTeam/AdguardFilters/master/MobileFilter/sections/adservers.txt",
    enabled: true,
    count: 0,
    lastUpdated: null
  },
  {
    id: "sub_oisd_basic",
    name: "OISD Essential Blocklist",
    url: "https://small.oisd.nl",
    enabled: true,
    count: 0,
    lastUpdated: null
  },
  {
    id: "sub_dan_pollock",
    name: "Dan Pollock's Hosts List",
    url: "https://someonewhocares.org/hosts/hosts",
    enabled: true,
    count: 0,
    lastUpdated: null
  }
];

const SYNC_ALARM_NAME = "antigravity_subscriptions_sync";

// Default State Initialization
chrome.runtime.onInstalled.addListener(async () => {
  const data = await chrome.storage.local.get([
    "enabled",
    "blockedCountTotal",
    "blockedCountSession",
    "whitelistedDomains",
    "lastSyncTime",
    "subscriptions",
    "totalDomainCount"
  ]);

  // Merge default subscriptions with any existing subscriptions
  let mergedSubscriptions = data.subscriptions && data.subscriptions.length > 0
    ? [...data.subscriptions]
    : [];

  // Add any default subscription that doesn't exist yet
  DEFAULT_SUBSCRIPTIONS.forEach(defaultSub => {
    if (!mergedSubscriptions.some(s => s.id === defaultSub.id || s.url === defaultSub.url)) {
      mergedSubscriptions.push(defaultSub);
    }
  });

  const defaults = {
    enabled: data.enabled !== undefined ? data.enabled : true,
    blockedCountTotal: data.blockedCountTotal || 0,
    blockedCountSession: 0,
    whitelistedDomains: data.whitelistedDomains || [],
    lastSyncTime: data.lastSyncTime || Date.now(),
    subscriptions: mergedSubscriptions,
    totalDomainCount: data.totalDomainCount || 209
  };

  await chrome.storage.local.set(defaults);
  updateBadge(defaults.enabled, 0);

  // Setup periodic sync every 24 hours (1440 minutes)
  chrome.alarms.create(SYNC_ALARM_NAME, { periodInMinutes: 1440 });

  // Initial sync all enabled subscriptions
  await syncAllSubscriptions(false);
});

// Also check for updates on browser startup
chrome.runtime.onStartup.addListener(async () => {
  const data = await chrome.storage.local.get(["lastSyncTime"]);
  const oneDayAgo = Date.now() - (24 * 60 * 60 * 1000);
  if (!data.lastSyncTime || data.lastSyncTime < oneDayAgo) {
    syncAllSubscriptions(false);
  }
});

// Periodic sync alarm listener
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === SYNC_ALARM_NAME) {
    syncAllSubscriptions(false);
  }
});

// Track Blocked Requests via DNR Debug Listener
if (chrome.declarativeNetRequest.onRuleMatchedDebug) {
  chrome.declarativeNetRequest.onRuleMatchedDebug.addListener(async (info) => {
    const data = await chrome.storage.local.get(["blockedCountTotal", "blockedCountSession", "enabled"]);
    if (data.enabled !== false) {
      const newSession = (data.blockedCountSession || 0) + 1;
      const newTotal = (data.blockedCountTotal || 0) + 1;
      await chrome.storage.local.set({
        blockedCountSession: newSession,
        blockedCountTotal: newTotal
      });
      updateBadge(true, newSession);
    }
  });
}

// Update Badge UI
function updateBadge(enabled, count) {
  if (!enabled) {
    chrome.action.setBadgeText({ text: "OFF" });
    chrome.action.setBadgeBackgroundColor({ color: "#64748B" });
  } else {
    chrome.action.setBadgeText({ text: count > 0 ? (count > 999 ? "999+" : String(count)) : "ON" });
    chrome.action.setBadgeBackgroundColor({ color: "#06B6D4" });
  }
}

// Clean and Normalize Domain (Strips protocol, www., paths, ports, leading/trailing symbols)
function cleanAndNormalizeDomain(raw) {
  if (!raw) return null;
  let domain = raw.trim().toLowerCase();

  // Strip comments, ABP symbols, and protocols
  domain = domain.replace(/^https?:\/\//i, "");
  domain = domain.replace(/^\|\|/, "");
  domain = domain.replace(/\^.*$/, "");
  domain = domain.replace(/^\*\./, "");
  domain = domain.split("/")[0].split(":")[0];
  domain = domain.replace(/^www\./i, "");
  domain = domain.replace(/\.+$/, "");

  // Domain validation regex
  const validDomainPattern = /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$/i;
  if (!validDomainPattern.test(domain)) return null;

  // Filter out loopback / local names
  if (domain === "localhost" || domain === "local" || domain === "broadcasthost") return null;

  return domain;
}

// Universal Filter List Parser: parses ABP, Hosts, and plain domain lists
function parseFilterList(text) {
  const lines = text.split("\n");
  const domains = new Set();

  for (let rawLine of lines) {
    let line = rawLine.trim();
    if (!line || line.startsWith("#") || line.startsWith("!") || line.startsWith("[Adblock")) {
      continue;
    }

    // 1. Hosts file format (e.g. "0.0.0.0 example.com" or "127.0.0.1 example.com")
    const hostsMatch = line.match(/^(?:0\.0\.0\.0|127\.0\.0\.1)\s+([^\s#]+)/);
    if (hostsMatch) {
      const clean = cleanAndNormalizeDomain(hostsMatch[1]);
      if (clean) domains.add(clean);
      continue;
    }

    // 2. ABP filter syntax: ||domain.com^
    const abpMatch = line.match(/^\|\|([^\^\/\$#]+)/);
    if (abpMatch) {
      const clean = cleanAndNormalizeDomain(abpMatch[1]);
      if (clean) domains.add(clean);
      continue;
    }

    // 3. Plain domain format
    const clean = cleanAndNormalizeDomain(line);
    if (clean) {
      domains.add(clean);
    }
  }

  return Array.from(domains);
}

// Prune Redundant Subdomains: if "example.com" is blocked, "ad.example.com" is redundant (because ||example.com^ blocks all subdomains)
function pruneRedundantSubdomains(domains) {
  const domainSet = new Set(domains);
  const pruned = [];

  for (const domain of domains) {
    const parts = domain.split(".");
    let isRedundant = false;

    // Check if any parent domain exists in the set
    for (let i = 1; i < parts.length - 1; i++) {
      const parentDomain = parts.slice(i).join(".");
      if (domainSet.has(parentDomain)) {
        isRedundant = true;
        break;
      }
    }

    if (!isRedundant) {
      pruned.push(domain);
    }
  }

  return pruned;
}

// Sync single subscription by URL
async function fetchSubscriptionRules(url) {
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const text = await response.text();
  return parseFilterList(text);
}

// Sync All Subscriptions & Rebuild Dynamic DNR Rules with Strict Deduplication
async function syncAllSubscriptions(isUserInitiated = false) {
  try {
    const data = await chrome.storage.local.get(["subscriptions"]);
    const subscriptions = data.subscriptions || DEFAULT_SUBSCRIPTIONS;

    // Set ensures strict deduplication across ALL subscription URLs
    const allDomains = new Set();
    const updatedSubscriptions = [];

    for (let sub of subscriptions) {
      if (sub.enabled) {
        try {
          const domains = await fetchSubscriptionRules(sub.url);
          domains.forEach(d => allDomains.add(d));
          updatedSubscriptions.push({
            ...sub,
            count: domains.length,
            lastUpdated: Date.now(),
            lastError: null
          });
        } catch (err) {
          console.warn(`Failed syncing subscription ${sub.name}:`, err);
          updatedSubscriptions.push({
            ...sub,
            lastError: err.message
          });
        }
      } else {
        updatedSubscriptions.push(sub);
      }
    }

    // Convert Set to Array and Prune Redundant Subdomains
    const uniqueDomainList = Array.from(allDomains);
    const optimizedDomainList = pruneRedundantSubdomains(uniqueDomainList);

    // Convert aggregated domains to Declarative Net Request dynamic rules
    // Chrome allows up to 30,000 dynamic rules in MV3
    const maxDynamicRules = 25000;
    const safeDomainList = optimizedDomainList.slice(0, maxDynamicRules);

    const resourceTypes = [
      "main_frame", "sub_frame", "stylesheet", "script",
      "image", "font", "object", "xmlhttprequest",
      "ping", "media", "websocket", "other"
    ];

    const newRules = safeDomainList.map((domain, index) => ({
      id: index + 1000,
      priority: 1,
      action: { type: "block" },
      condition: {
        urlFilter: `||${domain}^`,
        resourceTypes: resourceTypes
      }
    }));

    // Replace all dynamic rules
    const oldRules = await chrome.declarativeNetRequest.getDynamicRules();
    const removeRuleIds = oldRules.map(r => r.id);

    await chrome.declarativeNetRequest.updateDynamicRules({
      removeRuleIds: removeRuleIds,
      addRules: newRules
    });

    const now = Date.now();
    await chrome.storage.local.set({
      lastSyncTime: now,
      subscriptions: updatedSubscriptions,
      totalDomainCount: safeDomainList.length
    });

    return {
      success: true,
      count: safeDomainList.length,
      subscriptions: updatedSubscriptions,
      timestamp: now
    };
  } catch (err) {
    console.error("Antigravity AdBlock Subscription Sync failed:", err);
    return {
      success: false,
      error: err.message
    };
  }
}

// Message Listener for Popup Interaction
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === "getStatus") {
    chrome.storage.local.get([
      "enabled",
      "blockedCountTotal",
      "blockedCountSession",
      "whitelistedDomains",
      "lastSyncTime",
      "subscriptions",
      "totalDomainCount"
    ]).then(sendResponse);
    return true;
  }

  if (message.action === "toggleProtection") {
    const newState = message.enabled;
    chrome.storage.local.set({ enabled: newState }).then(async () => {
      await chrome.declarativeNetRequest.updateEnabledRulesets({
        disableRulesetIds: newState ? [] : ["ruleset_1"],
        enableRulesetIds: newState ? ["ruleset_1"] : []
      });

      const data = await chrome.storage.local.get("blockedCountSession");
      updateBadge(newState, data.blockedCountSession || 0);
      sendResponse({ success: true, enabled: newState });
    });
    return true;
  }

  if (message.action === "syncAllSubscriptions") {
    syncAllSubscriptions(true).then(sendResponse);
    return true;
  }

  if (message.action === "addSubscription") {
    const { name, url } = message;
    chrome.storage.local.get(["subscriptions"]).then(async (data) => {
      const subs = data.subscriptions || DEFAULT_SUBSCRIPTIONS;
      const newSub = {
        id: "sub_" + Date.now(),
        name: name || "Custom Subscription",
        url: url.trim(),
        enabled: true,
        count: 0,
        lastUpdated: null
      };
      subs.push(newSub);
      await chrome.storage.local.set({ subscriptions: subs });
      // Trigger sync immediately for new subscription
      const syncResult = await syncAllSubscriptions(true);
      sendResponse({ success: true, subscriptions: subs, syncResult });
    });
    return true;
  }

  if (message.action === "toggleSubscription") {
    const { id, enabled } = message;
    chrome.storage.local.get(["subscriptions"]).then(async (data) => {
      const subs = (data.subscriptions || DEFAULT_SUBSCRIPTIONS).map(s => {
        if (s.id === id) return { ...s, enabled };
        return s;
      });
      await chrome.storage.local.set({ subscriptions: subs });
      const syncResult = await syncAllSubscriptions(true);
      sendResponse({ success: true, subscriptions: subs, syncResult });
    });
    return true;
  }

  if (message.action === "removeSubscription") {
    const { id } = message;
    chrome.storage.local.get(["subscriptions"]).then(async (data) => {
      const subs = (data.subscriptions || DEFAULT_SUBSCRIPTIONS).filter(s => s.id !== id);
      await chrome.storage.local.set({ subscriptions: subs });
      const syncResult = await syncAllSubscriptions(true);
      sendResponse({ success: true, subscriptions: subs, syncResult });
    });
    return true;
  }

  if (message.action === "toggleWhitelist") {
    const domain = message.domain;
    chrome.storage.local.get(["whitelistedDomains"]).then(async (data) => {
      let list = data.whitelistedDomains || [];
      const index = list.indexOf(domain);
      let isWhitelisted = false;

      if (index === -1) {
        list.push(domain);
        isWhitelisted = true;
      } else {
        list.splice(index, 1);
        isWhitelisted = false;
      }

      await chrome.storage.local.set({ whitelistedDomains: list });
      sendResponse({ success: true, isWhitelisted, whitelistedDomains: list });
    });
    return true;
  }
});