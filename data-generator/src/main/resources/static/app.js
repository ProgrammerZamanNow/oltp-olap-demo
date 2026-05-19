/* =================================================================== */
/* OLTP → OLAP Stream Inspector                                          */
/* =================================================================== */

const API_BASE = '/api';
const AUTO_REFRESH_MS = 5000;

const VIEWS = {
  customers: {
    endpoint: '/customers',
    columns: [
      { key: 'id',        label: 'ID',        cls: 'id num' },
      { key: 'name',      label: 'NAME' },
      { key: 'email',     label: 'EMAIL',     cls: 'email' },
      { key: 'city',      label: 'CITY' },
      { key: 'createdAt', label: 'CREATED',   cls: 'time', fmt: fmtTime },
      { key: 'updatedAt', label: 'UPDATED',   cls: 'time', fmt: fmtTime },
    ],
  },
  products: {
    endpoint: '/products',
    columns: [
      { key: 'id',        label: 'ID',        cls: 'id num' },
      { key: 'name',      label: 'NAME' },
      { key: 'category',  label: 'CATEGORY',  fmt: fmtCategory },
      { key: 'price',     label: 'PRICE',     cls: 'num money', fmt: fmtMoney },
      { key: 'stock',     label: 'STOCK',     cls: 'num',       fmt: fmtNum },
      { key: 'createdAt', label: 'CREATED',   cls: 'time',      fmt: fmtTime },
      { key: 'updatedAt', label: 'UPDATED',   cls: 'time',      fmt: fmtTime },
    ],
  },
  orders: {
    endpoint: '/orders',
    columns: [
      { key: 'id',          label: 'ID',          cls: 'id num' },
      { key: 'customerId',  label: 'CUSTOMER #',  cls: 'num' },
      { key: 'status',      label: 'STATUS',      fmt: fmtStatus },
      { key: 'totalAmount', label: 'TOTAL',       cls: 'num money', fmt: fmtMoney },
      { key: 'createdAt',   label: 'CREATED',     cls: 'time',      fmt: fmtTime },
      { key: 'updatedAt',   label: 'UPDATED',     cls: 'time',      fmt: fmtTime },
    ],
  },
};

const state = {
  view: 'customers',
  page: 0,
  size: 100,
  sort: 'createdAt,desc',
  total: 0,
  totalPages: 0,
  counts: { customers: 0, products: 0, orders: 0 },
  loading: false,
  inFlight: 0,
};

let autoRefreshTimer = null;

/* ---------------- Formatters ---------------- */

function escapeHtml(s) {
  if (s == null) return '';
  return String(s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function fmtNum(v) {
  if (v == null || v === '') return '—';
  return new Intl.NumberFormat('id-ID').format(v);
}

function fmtMoney(v) {
  if (v == null || v === '') return '—';
  return 'Rp ' + new Intl.NumberFormat('id-ID', {
    maximumFractionDigits: 0,
  }).format(v);
}

function fmtTime(v) {
  if (!v) return '—';
  const d = new Date(v);
  if (isNaN(d.getTime())) return escapeHtml(v);
  const pad = n => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  return `<span class="t-date">${date}</span>&nbsp;<span class="t-time">${time}</span>`;
}

function fmtStatus(v) {
  const safe = String(v || '').toUpperCase();
  const cls = safe.toLowerCase() || 'placed';
  return `<span class="badge ${cls}">${escapeHtml(safe)}</span>`;
}

function fmtCategory(v) {
  return `<span class="cat">${escapeHtml(v || '—')}</span>`;
}

/* ---------------- Data loading ---------------- */

async function fetchPage(view, opts = {}) {
  const url = new URL(API_BASE + VIEWS[view].endpoint, location.origin);
  url.searchParams.set('page', opts.page ?? 0);
  url.searchParams.set('size', opts.size ?? 100);
  if (opts.sort) url.searchParams.set('sort', opts.sort);
  const res = await fetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

async function load(options = { animate: true }) {
  const view = VIEWS[state.view];
  if (!view) return;

  state.inFlight++;
  const myToken = state.inFlight;
  setLoading(true);

  const t0 = performance.now();
  try {
    const data = await fetchPage(state.view, {
      page: state.page,
      size: state.size,
      sort: state.sort,
    });

    // discard stale response (newer request was fired)
    if (myToken !== state.inFlight) return;

    state.total = data.totalElements ?? 0;
    state.totalPages = data.totalPages ?? 0;
    state.counts[state.view] = state.total;

    render(view, data.content || [], options.animate);

    const fetchMs = Math.round(performance.now() - t0);
    setLastUpdate(fetchMs);
    refreshMeta();
    refreshCounts(state.view);
  } catch (err) {
    console.error('[load]', err);
    showError(err);
  } finally {
    if (myToken === state.inFlight) setLoading(false);
  }
}

async function refreshAllCounts() {
  for (const v of Object.keys(VIEWS)) {
    try {
      const data = await fetchPage(v, { page: 0, size: 1 });
      const before = state.counts[v];
      const after = data.totalElements ?? 0;
      state.counts[v] = after;
      paintCount(v, after, after > before);
    } catch (e) { /* ignore */ }
  }
}

/* ---------------- Rendering ---------------- */

function render(view, rows, animate) {
  const thead = document.querySelector('#dataTable thead');
  const tbody = document.querySelector('#dataTable tbody');
  const empty = document.getElementById('emptyState');

  empty.classList.remove('error');

  thead.innerHTML =
    '<tr>' +
    view.columns
      .map(c => `<th class="${c.cls || ''}"><span class="col-mark">›</span>${c.label}</th>`)
      .join('') +
    '</tr>';

  if (!rows.length) {
    tbody.innerHTML = '';
    empty.textContent = '── NO DATA ──';
    empty.classList.add('show');
    return;
  }
  empty.classList.remove('show');

  tbody.innerHTML = rows
    .map((row, idx) => {
      const delay = animate ? Math.min(idx * 8, 200) : 0;
      const tds = view.columns
        .map(c => {
          const v = row[c.key];
          const display = c.fmt ? c.fmt(v) : escapeHtml(v);
          return `<td class="${c.cls || ''}">${display}</td>`;
        })
        .join('');
      return `<tr style="animation-delay:${delay}ms">${tds}</tr>`;
    })
    .join('');
}

function refreshMeta() {
  el('currentView').textContent = state.view.toUpperCase();
  el('totalCount').textContent = fmtNum(state.total);

  if (state.total === 0) {
    el('showingRange').textContent = '0';
  } else {
    const start = state.page * state.size + 1;
    const end = Math.min((state.page + 1) * state.size, state.total);
    el('showingRange').textContent = `${fmtNum(start)} – ${fmtNum(end)}`;
  }

  el('pageNum').textContent = fmtNum(state.page + 1);
  el('pageTotal').textContent = fmtNum(Math.max(state.totalPages, 1));

  el('firstBtn').disabled = state.page === 0;
  el('prevBtn').disabled  = state.page === 0;
  el('nextBtn').disabled  = state.page >= state.totalPages - 1;
  el('lastBtn').disabled  = state.page >= state.totalPages - 1;

  const [field, dir] = state.sort.split(',');
  el('sortDisplay').textContent = `${field} ${dir === 'asc' ? '↑' : '↓'}`;
}

function paintCount(view, count, didIncrease) {
  const node = document.querySelector(`[data-count="${view}"]`);
  if (!node) return;
  node.textContent = fmtNum(count);
  if (didIncrease) {
    node.classList.remove('flash');
    void node.offsetWidth; // restart anim
    node.classList.add('flash');
  }
}

function refreshCounts(currentView) {
  if (currentView) paintCount(currentView, state.counts[currentView], false);
}

function showError(err) {
  document.querySelector('#dataTable tbody').innerHTML = '';
  const empty = document.getElementById('emptyState');
  empty.textContent = '⚠ FETCH FAILED — ' + (err.message || err);
  empty.classList.add('show', 'error');
}

function setLoading(on) {
  state.loading = on;
  document.getElementById('tableWrap').classList.toggle('loading', on);
}

function setLastUpdate(fetchMs) {
  const now = new Date();
  const pad = n => String(n).padStart(2, '0');
  el('lastUpdate').textContent =
    `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
  el('fetchTime').textContent = `${fetchMs} ms`;
}

function el(id) { return document.getElementById(id); }

/* ---------------- View switching ---------------- */

function setView(view) {
  if (!VIEWS[view]) view = 'customers';
  state.view = view;
  state.page = 0;

  document.querySelectorAll('.nav-item').forEach(n => {
    n.classList.toggle('active', n.dataset.view === view);
  });

  load({ animate: true });
}

/* ---------------- Auto-refresh ---------------- */

function scheduleAutoRefresh() {
  clearTimeout(autoRefreshTimer);
  autoRefreshTimer = setTimeout(async () => {
    // Refresh counts always; refresh table only on page 0 (newest data)
    await refreshAllCounts();
    if (state.page === 0 && document.visibilityState === 'visible') {
      await load({ animate: false });
    }
    scheduleAutoRefresh();
  }, AUTO_REFRESH_MS);
}

/* ---------------- Wiring ---------------- */

function init() {
  document.querySelectorAll('.nav-item').forEach(item => {
    item.addEventListener('click', e => {
      e.preventDefault();
      location.hash = item.dataset.view;
    });
  });

  window.addEventListener('hashchange', () => {
    const v = (location.hash || '#customers').slice(1);
    setView(v);
  });

  el('firstBtn').addEventListener('click', () => { state.page = 0; load(); });
  el('prevBtn').addEventListener('click', () => {
    state.page = Math.max(0, state.page - 1); load();
  });
  el('nextBtn').addEventListener('click', () => {
    state.page = Math.min(state.totalPages - 1, state.page + 1); load();
  });
  el('lastBtn').addEventListener('click', () => {
    state.page = Math.max(0, state.totalPages - 1); load();
  });

  el('sortSelect').addEventListener('change', e => {
    state.sort = e.target.value;
    state.page = 0;
    load();
  });

  el('sizeSelect').addEventListener('change', e => {
    state.size = parseInt(e.target.value, 10);
    state.page = 0;
    load();
  });

  el('refreshBtn').addEventListener('click', () => {
    const btn = el('refreshBtn');
    btn.classList.remove('spinning');
    void btn.offsetWidth;
    btn.classList.add('spinning');
    refreshAllCounts();
    load({ animate: false });
  });

  // Keyboard shortcuts
  document.addEventListener('keydown', e => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
    if (e.key === 'ArrowRight' && !el('nextBtn').disabled) el('nextBtn').click();
    if (e.key === 'ArrowLeft'  && !el('prevBtn').disabled) el('prevBtn').click();
    if (e.key === 'r' || e.key === 'R') el('refreshBtn').click();
    if (e.key === '1') location.hash = 'customers';
    if (e.key === '2') location.hash = 'products';
    if (e.key === '3') location.hash = 'orders';
  });

  // Boot
  const initialView = (location.hash || '#customers').slice(1);
  if (!location.hash) location.hash = initialView;
  setView(initialView);
  refreshAllCounts();
  scheduleAutoRefresh();
}

document.addEventListener('DOMContentLoaded', init);
