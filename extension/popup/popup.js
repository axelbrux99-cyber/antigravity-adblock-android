// Antigravity AdBlock - Popup UI Logic
document.addEventListener("DOMContentLoaded", async () => {
  // Navigation
  const tabBtnHome = document.getElementById("tab-btn-home");
  const tabBtnSubs = document.getElementById("tab-btn-subs");
  const panelHome = document.getElementById("panel-home");
  const panelSubs = document.getElementById("panel-subs");
  const subsCountBadge = document.getElementById("subs-count-badge");

  // Home Controls
  const toggleProtection = document.getElementById("toggle-protection");
  const statusPill = document.getElementById("status-pill");
  const statusPillText = document.getElementById("status-pill-text");
  const shieldCircle = document.getElementById("shield-circle");
  const protectionTitle = document.getElementById("protection-title");
  const protectionSub = document.getElementById("protection-sub");
  const blockedCountEl = document.getElementById("blocked-count");
  const domainCountEl = document.getElementById("domain-count");
  const currentSiteHostEl = document.getElementById("current-site-host");
  const btnToggleSite = document.getElementById("btn-toggle-site");
  const siteBtnText = document.getElementById("site-btn-text");
  const btnSyncAll = document.getElementById("btn-sync-all");
  const syncIcon = document.getElementById("sync-icon");
  const lastSyncTimeEl = document.getElementById("last-sync-time");

  // Subscription Controls
  const inputSubName = document.getElementById("input-sub-name");
  const inputSubUrl = document.getElementById("input-sub-url");
  const btnAddSubscription = document.getElementById("btn-add-subscription");
  const subsListContainer = document.getElementById("subs-list-container");

  let currentHost = "";
  let isWhitelisted = false;
  let currentSubscriptions = [];

  // Tab Navigation Handling
  tabBtnHome.addEventListener("click", () => {
    tabBtnHome.classList.add("active");
    tabBtnSubs.classList.remove("active");
    panelHome.classList.add("active");
    panelSubs.classList.remove("active");
  });

  tabBtnSubs.addEventListener("click", () => {
    tabBtnSubs.classList.add("active");
    tabBtnHome.classList.remove("active");
    panelSubs.classList.add("active");
    panelHome.classList.remove("active");
  });

  // 1. Get Current Tab URL
  try {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs && tabs[0] && tabs[0].url) {
      const urlObj = new URL(tabs[0].url);
      currentHost = urlObj.hostname;
      currentSiteHostEl.textContent = currentHost || "Lokal / Internal";
    }
  } catch (e) {
    currentSiteHostEl.textContent = "Halaman Browser";
  }

  // 2. Fetch Initial State from Service Worker
  loadStatus();

  function loadStatus() {
    chrome.runtime.sendMessage({ action: "getStatus" }, (response) => {
      if (!response) return;

      const enabled = response.enabled !== false;
      toggleProtection.checked = enabled;
      updateUIState(enabled);

      blockedCountEl.textContent = (response.blockedCountTotal || 0).toLocaleString();
      domainCountEl.textContent = (response.totalDomainCount || 209).toLocaleString();

      if (response.lastSyncTime) {
        const date = new Date(response.lastSyncTime);
        const hours = String(date.getHours()).padStart(2, '0');
        const mins = String(date.getMinutes()).padStart(2, '0');
        lastSyncTimeEl.textContent = `Sync: ${hours}:${mins}`;
      }

      currentSubscriptions = response.subscriptions || [];
      subsCountBadge.textContent = currentSubscriptions.filter(s => s.enabled).length;
      renderSubscriptions(currentSubscriptions);

      // Check Whitelist for current host
      if (response.whitelistedDomains && currentHost) {
        isWhitelisted = response.whitelistedDomains.includes(currentHost);
        updateSiteButton(isWhitelisted);
      }
    });
  }

  // 3. Toggle Global Protection
  toggleProtection.addEventListener("change", () => {
    const isChecked = toggleProtection.checked;
    chrome.runtime.sendMessage({ action: "toggleProtection", enabled: isChecked }, (res) => {
      updateUIState(isChecked);
    });
  });

  // 4. Toggle Site Whitelist
  btnToggleSite.addEventListener("click", () => {
    if (!currentHost) return;
    chrome.runtime.sendMessage({ action: "toggleWhitelist", domain: currentHost }, (res) => {
      if (res && res.success) {
        isWhitelisted = res.isWhitelisted;
        updateSiteButton(isWhitelisted);
      }
    });
  });

  // 5. Update All Subscriptions Button
  btnSyncAll.addEventListener("click", () => {
    btnSyncAll.disabled = true;
    syncIcon.classList.add("spinning");

    chrome.runtime.sendMessage({ action: "syncAllSubscriptions" }, (res) => {
      btnSyncAll.disabled = false;
      syncIcon.classList.remove("spinning");

      if (res && res.success) {
        domainCountEl.textContent = res.count.toLocaleString();
        const date = new Date(res.timestamp);
        const hours = String(date.getHours()).padStart(2, '0');
        const mins = String(date.getMinutes()).padStart(2, '0');
        lastSyncTimeEl.textContent = `Sync: ${hours}:${mins}`;
        if (res.subscriptions) {
          renderSubscriptions(res.subscriptions);
        }
        alert(`Berhasil memperbarui semua langganan! Total ${res.count.toLocaleString()} domain aktif.`);
      } else {
        alert("Gagal memperbarui langganan. Periksa koneksi internet Anda.");
      }
    });
  });

  // 6. Add Custom Subscription
  btnAddSubscription.addEventListener("click", () => {
    const name = inputSubName.value.trim();
    const url = inputSubUrl.value.trim();

    if (!url || (!url.startsWith("http://") && !url.startsWith("https://"))) {
      alert("Masukkan URL subscription yang valid (dimulai dengan https://)");
      return;
    }

    btnAddSubscription.disabled = true;
    btnAddSubscription.textContent = "Mengunduh filter...";

    chrome.runtime.sendMessage({ action: "addSubscription", name, url }, (res) => {
      btnAddSubscription.disabled = false;
      btnAddSubscription.innerHTML = `
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
        <span>Tambah & Perbarui Filter</span>
      `;

      if (res && res.success) {
        inputSubName.value = "";
        inputSubUrl.value = "";
        currentSubscriptions = res.subscriptions;
        subsCountBadge.textContent = currentSubscriptions.filter(s => s.enabled).length;
        renderSubscriptions(currentSubscriptions);
        loadStatus();
        alert("Subscription berhasil ditambahkan dan filter telah diperbarui!");
      } else {
        alert("Gagal menambahkan subscription.");
      }
    });
  });

  // Render Subscriptions List
  function renderSubscriptions(subs) {
    subsListContainer.innerHTML = "";
    if (!subs || subs.length === 0) {
      subsListContainer.innerHTML = `<div style="text-align:center;font-size:11px;color:#64748B;padding:12px;">Belum ada langganan filter.</div>`;
      return;
    }

    subs.forEach((sub) => {
      const card = document.createElement("div");
      card.className = "sub-item-card";

      const mainInfo = document.createElement("div");
      mainInfo.className = "sub-item-main";
      mainInfo.innerHTML = `
        <div class="sub-item-name">${escapeHtml(sub.name)}</div>
        <div class="sub-item-meta">
          <span>${(sub.count || 0).toLocaleString()} rules</span>
          ${sub.lastError ? `<span style="color:#EF4444">&bull; Error</span>` : `<span>&bull; Siap</span>`}
        </div>
      `;

      const actions = document.createElement("div");
      actions.className = "sub-item-actions";

      // Toggle switch
      const switchLabel = document.createElement("label");
      switchLabel.className = "mini-switch";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = sub.enabled !== false;
      checkbox.addEventListener("change", () => {
        chrome.runtime.sendMessage({
          action: "toggleSubscription",
          id: sub.id,
          enabled: checkbox.checked
        }, (res) => {
          if (res && res.subscriptions) {
            currentSubscriptions = res.subscriptions;
            subsCountBadge.textContent = currentSubscriptions.filter(s => s.enabled).length;
            loadStatus();
          }
        });
      });

      const miniSlider = document.createElement("span");
      miniSlider.className = "mini-slider";
      switchLabel.appendChild(checkbox);
      switchLabel.appendChild(miniSlider);
      actions.appendChild(switchLabel);

      // Delete button (for custom subscriptions only)
      if (sub.id !== "sub_antigravity_default") {
        const delBtn = document.createElement("button");
        delBtn.className = "btn-del-sub";
        delBtn.title = "Hapus Langganan";
        delBtn.innerHTML = `
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="3 6 5 6 21 6"></polyline>
            <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
          </svg>
        `;
        delBtn.addEventListener("click", () => {
          if (confirm(`Hapus langganan "${sub.name}"?`)) {
            chrome.runtime.sendMessage({ action: "removeSubscription", id: sub.id }, (res) => {
              if (res && res.subscriptions) {
                currentSubscriptions = res.subscriptions;
                subsCountBadge.textContent = currentSubscriptions.filter(s => s.enabled).length;
                renderSubscriptions(currentSubscriptions);
                loadStatus();
              }
            });
          }
        });
        actions.appendChild(delBtn);
      }

      card.appendChild(mainInfo);
      card.appendChild(actions);
      subsListContainer.appendChild(card);
    });
  }

  // Helper: Update UI Visuals
  function updateUIState(enabled) {
    if (enabled) {
      statusPill.classList.remove("disabled");
      statusPill.classList.add("active");
      statusPillText.textContent = "Aktif";

      shieldCircle.classList.remove("disabled");
      shieldCircle.classList.add("glow");

      protectionTitle.textContent = "Perlindungan Aktif";
      protectionSub.textContent = "Iklan & tracker diblokir secara otomatis";
    } else {
      statusPill.classList.remove("active");
      statusPill.classList.add("disabled");
      statusPillText.textContent = "Nonaktif";

      shieldCircle.classList.remove("glow");
      shieldCircle.classList.add("disabled");

      protectionTitle.textContent = "Perlindungan Dijeda";
      protectionSub.textContent = "Iklan diizinkan lewat";
    }
  }

  function updateSiteButton(paused) {
    if (paused) {
      btnToggleSite.classList.add("paused");
      siteBtnText.textContent = "Lanjutkan Proteksi";
    } else {
      btnToggleSite.classList.remove("paused");
      siteBtnText.textContent = "Pause di Situs Ini";
    }
  }

  function escapeHtml(str) {
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }
});