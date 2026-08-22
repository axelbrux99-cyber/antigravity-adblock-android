// Antigravity AdBlock - Content Script for Cosmetic Cleanup
(async function() {
  const currentHost = window.location.hostname;
  
  // Check if current site is whitelisted
  try {
    const data = await chrome.storage.local.get(["enabled", "whitelistedDomains"]);
    if (data.enabled === false) return;
    if (data.whitelistedDomains && data.whitelistedDomains.includes(currentHost)) return;
  } catch (e) {
    // Context invalidated or standalone
  }

  function cleanAdElements() {
    const selectors = [
      'ins.adsbygoogle',
      'div[id^="google_ads_iframe"]',
      'div[id^="div-gpt-ad"]',
      'iframe[src*="doubleclick.net"]',
      'iframe[src*="googleads"]',
      'iframe[src*="taboola.com"]',
      'iframe[src*="outbrain.com"]'
    ];

    document.querySelectorAll(selectors.join(',')).forEach(el => {
      el.style.setProperty('display', 'none', 'important');
    });
  }

  // Run on DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', cleanAdElements);
  } else {
    cleanAdElements();
  }

  // MutationObserver for SPA / dynamically injected ad elements
  const observer = new MutationObserver(() => {
    cleanAdElements();
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true
  });
})();