import { initDownloadManager } from './admin_downloads.js';
import { initSystemMonitor } from './system_monitor.js';
import { initUpdateProgress } from './update_progress.js';
import { t } from './ui.js';

function initUpdateToast() {
  const toast = document.getElementById('update-available-toast');
  if (!toast || toast.dataset.initialized === 'true') return;
  toast.dataset.initialized = 'true';

  const toastMessage = document.getElementById('update-toast-message');
  const dismissButton = document.getElementById('update-toast-dismiss');
  const openButton = document.getElementById('update-toast-open');
  let pollTimer = null;
  let pollAttempts = 0;
  let polling = false;

  async function loadUpdateNotification() {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch('/admin/api/update-notification', {
        credentials: 'same-origin',
        signal: controller.signal
      });
      if (response.status === 401 || response.status === 403) return false;
      if (!response.ok) return true;
      const data = await response.json();
      if (!data.update_available || !data.remote_full_commit) return true;

      const dismissKey = `aoede-dismissed-update:${data.remote_full_commit}`;
      try {
        if (localStorage.getItem(dismissKey) === '1') return false;
      } catch {}

      const versionLabel = data.remote_version || data.remote_release_version || data.remote_commit || 'unknown';
      toastMessage.textContent = t(
        'updates.server_message',
        'Server {version} is available. Open Updates to review and install it.',
        { version: versionLabel }
      );
      toast.style.display = 'block';

      dismissButton.onclick = () => {
        try {
          localStorage.setItem(dismissKey, '1');
        } catch {}
        toast.style.display = 'none';
      };
      openButton.onclick = () => {
        toast.style.display = 'none';
        window.location.href = '/admin/system?tab=updates';
      };
      return false;
    } catch (_error) {
      // Ignore notification failures.
      return true;
    } finally {
      clearTimeout(timeout);
    }
  }

  async function startNotificationPolling() {
    if (document.hidden || polling) return;
    clearTimeout(pollTimer);
    polling = true;
    const pending = await loadUpdateNotification();
    polling = false;
    pollAttempts++;
    pollTimer = setTimeout(startNotificationPolling, pending && pollAttempts < 12 ? 5000 : 300000);
  }
  document.addEventListener('visibilitychange', startNotificationPolling);
  startNotificationPolling();
}

function initAdminSystem() {
  initUpdateToast();
  initDownloadManager(document);
  initSystemMonitor(document);
  initUpdateProgress(document);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initAdminSystem);
} else {
  initAdminSystem();
}
