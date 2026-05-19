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
  const isStreaming = view === 'streaming';
  const isFunnel = view === 'funnel';
  const isDistribution = view === 'distribution';
  const isDashboard = isStreaming || isFunnel || isDistribution;
  const isInspector = !isDashboard && !!VIEWS[view];
  if (!isDashboard && !isInspector) view = 'customers';

  state.view = view;
  state.page = 0;

  document.querySelectorAll('.nav-item').forEach(n => {
    n.classList.toggle('active', n.dataset.view === view);
  });

  // toggle view containers + body mode class
  document.getElementById('inspectorView').hidden = isDashboard;
  document.getElementById('streamingView').hidden = !isStreaming;
  document.getElementById('funnelView').hidden = !isFunnel;
  document.getElementById('distributionView').hidden = !isDistribution;
  document.body.classList.toggle('mode-streaming', isDashboard);

  // deactivate everything first, then activate the one we want
  streamingDeactivate();
  funnelDeactivate();
  distributionDeactivate();

  if (isStreaming) streamingActivate();
  else if (isFunnel) funnelActivate();
  else if (isDistribution) distributionActivate();
  else load({ animate: true });
}

/* ---------------- Auto-refresh ---------------- */

function scheduleAutoRefresh() {
  clearTimeout(autoRefreshTimer);
  autoRefreshTimer = setTimeout(async () => {
    await refreshAllCounts();
    const inDashboard = state.view === 'streaming'
                     || state.view === 'funnel'
                     || state.view === 'distribution';
    if (!inDashboard
        && state.page === 0
        && document.visibilityState === 'visible') {
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

/* =================================================================== */
/* STREAMING DASHBOARD — ClickHouse time-series patterns                 */
/* =================================================================== */

const STREAM_REFRESH_MS = 2000;
const METRICS_API = '/api/metrics';

const charts = {
  throughput: null,
  velocity: null,
  anomaly: null,
};

const streamState = {
  active: false,
  timer: null,
  inFlight: 0,
  withFill: true,
};

/* ---------------- Chart base (dark + mono theme) ---------------- */

function baseChartOption() {
  return {
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'JetBrains Mono', fontSize: 11, color: '#a4a4ad' },
    animation: false,
    grid: { left: 50, right: 24, top: 14, bottom: 28, containLabel: true },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: '#2a2a36' } },
      axisTick: { lineStyle: { color: '#2a2a36' } },
      axisLabel: {
        color: '#6c6c78', fontSize: 10,
        formatter: (v) => {
          const d = new Date(v);
          const pad = (n) => String(n).padStart(2, '0');
          return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
        },
      },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#6c6c78', fontSize: 10 },
      splitLine: { lineStyle: { color: '#1a1a23', type: 'dashed' } },
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#0e0e13',
      borderColor: '#3d3d4e',
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: '#fafafa', fontFamily: 'JetBrains Mono', fontSize: 11 },
      axisPointer: { lineStyle: { color: '#ffb000', type: 'dashed' } },
    },
  };
}

function initCharts() {
  if (typeof echarts === 'undefined') return;
  const ids = ['Throughput', 'Velocity', 'Anomaly'];
  ids.forEach(name => {
    const id = 'chart' + name;
    const key = name.toLowerCase();
    if (charts[key]) return;
    const el = document.getElementById(id);
    if (!el) return;
    charts[key] = echarts.init(el, null, { renderer: 'canvas' });
  });
  // resize on window resize
  if (!window.__streamResizeBound) {
    window.addEventListener('resize', () => {
      Object.values(charts).forEach(c => c && c.resize());
    });
    window.__streamResizeBound = true;
  }
}

/* ---------------- Formatters ---------------- */

function fmtMoneyShort(v) {
  v = Number(v) || 0;
  if (v >= 1_000_000_000) return 'Rp ' + (v / 1_000_000_000).toFixed(1) + 'B';
  if (v >= 1_000_000)     return 'Rp ' + (v / 1_000_000).toFixed(1) + 'M';
  if (v >= 1_000)         return 'Rp ' + (v / 1_000).toFixed(0) + 'K';
  return 'Rp ' + new Intl.NumberFormat('id-ID').format(v);
}

function fmtCountShort(v) {
  return new Intl.NumberFormat('id-ID').format(Number(v) || 0);
}

function flashTile(id) {
  const node = document.getElementById(id);
  if (!node) return;
  node.classList.remove('flash');
  void node.offsetWidth;
  node.classList.add('flash');
}

/* ---------------- Tile update (multi-window) ---------------- */

function updateTiles(data, elapsedMs) {
  if (!data) return;
  const map = {
    'tile-orders-1m':  fmtCountShort(data.orders_1m),
    'tile-orders-5m':  fmtCountShort(data.orders_5m),
    'tile-orders-15m': fmtCountShort(data.orders_15m),
    'tile-orders-1h':  fmtCountShort(data.orders_1h),
    'tile-rev-1m':     fmtMoneyShort(data.rev_1m),
    'tile-rev-5m':     fmtMoneyShort(data.rev_5m),
    'tile-rev-15m':    fmtMoneyShort(data.rev_15m),
    'tile-rev-1h':     fmtMoneyShort(data.rev_1h),
  };
  for (const [id, val] of Object.entries(map)) {
    const node = document.getElementById(id);
    if (!node) continue;
    if (node.textContent !== val) {
      node.textContent = val;
      flashTile(id);
    }
  }
  setText('windowsElapsed', elapsedMs + ' ms');
}

/* ---------------- Throughput sparkline ---------------- */

function updateThroughput(rows, elapsedMs, withFill) {
  if (!charts.throughput) return;
  const points = rows.map(r => [r.minute, Number(r.orders) || 0]);
  const revPoints = rows.map(r => [r.minute, Number(r.revenue) || 0]);

  const opt = baseChartOption();
  opt.legend = {
    data: ['orders', 'revenue'],
    textStyle: { color: '#a4a4ad', fontSize: 10 },
    right: 12, top: 0,
    itemWidth: 14, itemHeight: 2,
  };
  opt.grid.top = 26;
  opt.yAxis = [
    {
      type: 'value',
      name: 'orders',
      nameTextStyle: { color: '#6c6c78', fontSize: 9, padding: [0, 0, 0, 0] },
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#6c6c78', fontSize: 10 },
      splitLine: { lineStyle: { color: '#1a1a23', type: 'dashed' } },
    },
    {
      type: 'value',
      name: 'Rp',
      nameTextStyle: { color: '#6c6c78', fontSize: 9 },
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: {
        color: '#6c6c78', fontSize: 10,
        formatter: (v) => v >= 1_000_000 ? (v / 1_000_000).toFixed(0) + 'M' : (v / 1000).toFixed(0) + 'K',
      },
      splitLine: { show: false },
    },
  ];
  opt.tooltip.formatter = (params) => {
    const ts = new Date(params[0].value[0]);
    const pad = (n) => String(n).padStart(2, '0');
    let html = `<div style="color:#fafafa;font-weight:700;margin-bottom:6px">`
      + `${pad(ts.getHours())}:${pad(ts.getMinutes())}</div>`;
    for (const p of params) {
      const isRev = p.seriesName === 'revenue';
      const v = isRev ? fmtMoneyShort(p.value[1]) : fmtCountShort(p.value[1]);
      html += `<div><span style="display:inline-block;width:8px;height:2px;background:${p.color};vertical-align:middle;margin-right:6px"></span>`
        + `<span style="color:#a4a4ad">${p.seriesName}</span> · <span style="color:#fafafa">${v}</span></div>`;
    }
    return html;
  };
  opt.series = [
    {
      name: 'orders',
      type: 'line',
      yAxisIndex: 0,
      smooth: 0.25,
      symbol: 'none',
      lineStyle: { color: '#ffb000', width: 1.8 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(255, 176, 0, 0.32)' },
          { offset: 1, color: 'rgba(255, 176, 0, 0)' },
        ]),
      },
      data: points,
    },
    {
      name: 'revenue',
      type: 'line',
      yAxisIndex: 1,
      smooth: 0.25,
      symbol: 'none',
      lineStyle: { color: '#ff7ad9', width: 1.2, type: 'dashed' },
      data: revPoints,
    },
  ];
  charts.throughput.setOption(opt, { notMerge: true });
  setText('throughputElapsed', elapsedMs + ' ms' + (withFill ? '' : ' · no fill'));
}

/* ---------------- Velocity (Δ orders/min) ---------------- */

function updateVelocity(rows, elapsedMs) {
  if (!charts.velocity) return;
  const opt = baseChartOption();
  opt.tooltip.formatter = (params) => {
    const p = params[0];
    const ts = new Date(p.value[0]);
    const pad = (n) => String(n).padStart(2, '0');
    const v = p.value[1];
    const sign = v > 0 ? '+' : '';
    return `<div style="color:#fafafa;font-weight:700;margin-bottom:4px">`
      + `${pad(ts.getHours())}:${pad(ts.getMinutes())}</div>`
      + `<div><span style="color:#a4a4ad">Δ orders</span> · `
      + `<span style="color:${v >= 0 ? '#5ee99d' : '#ff6b6b'};font-weight:700">${sign}${v}</span></div>`;
  };
  opt.series = [{
    type: 'bar',
    barCategoryGap: '30%',
    data: rows.map(r => {
      const v = Number(r.velocity) || 0;
      return {
        value: [r.minute, v],
        itemStyle: {
          color: v >= 0 ? '#5ee99d' : '#ff6b6b',
          opacity: v === 0 ? 0.2 : 0.85,
        },
      };
    }),
  }];
  charts.velocity.setOption(opt, { notMerge: true });
  setText('velocityElapsed', elapsedMs + ' ms');
}

/* ---------------- Anomaly z-score ---------------- */

function updateAnomaly(rows, elapsedMs) {
  if (!charts.anomaly) return;
  const opt = baseChartOption();
  opt.yAxis.min = -4;
  opt.yAxis.max = 4;
  opt.tooltip.formatter = (params) => {
    const p = params[0];
    const r = rows[p.dataIndex] || {};
    const ts = new Date(p.value[0]);
    const pad = (n) => String(n).padStart(2, '0');
    const z = p.value[1];
    return `<div style="color:#fafafa;font-weight:700;margin-bottom:4px">`
      + `${pad(ts.getHours())}:${pad(ts.getMinutes())}</div>`
      + `<div><span style="color:#a4a4ad">orders</span> · <span style="color:#fafafa">${r.orders ?? '—'}</span></div>`
      + `<div><span style="color:#a4a4ad">rolling avg</span> · <span style="color:#fafafa">${r.rolling_avg ?? '—'}</span></div>`
      + `<div><span style="color:#a4a4ad">rolling std</span> · <span style="color:#fafafa">${r.rolling_std ?? '—'}</span></div>`
      + `<div><span style="color:#a4a4ad">z-score</span> · `
      + `<span style="color:${Math.abs(z) >= 2 ? '#ff6b6b' : (Math.abs(z) >= 1 ? '#fbbf24' : '#6fbdff')};font-weight:700">${z ?? '—'}</span></div>`;
  };
  opt.series = [{
    type: 'bar',
    barCategoryGap: '30%',
    data: rows.map(r => {
      const z = Number(r.zscore);
      const valid = isFinite(z);
      return {
        value: [r.minute, valid ? z : 0],
        itemStyle: {
          color: !valid ? '#3d3d4e'
            : Math.abs(z) >= 2 ? '#ff6b6b'
            : Math.abs(z) >= 1 ? '#fbbf24'
            : '#6fbdff',
          opacity: !valid ? 0.3 : 0.9,
        },
      };
    }),
    markLine: {
      silent: true,
      symbol: 'none',
      lineStyle: { color: '#ff6b6b', type: 'dashed', opacity: 0.4 },
      label: { color: '#ff6b6b', fontSize: 9, formatter: '{c}σ' },
      data: [{ yAxis: 2 }, { yAxis: -2 }],
    },
  }];
  charts.anomaly.setOption(opt, { notMerge: true });
  setText('anomalyElapsed', elapsedMs + ' ms');
}

/* ---------------- Helpers ---------------- */

function setText(id, text) {
  const node = document.getElementById(id);
  if (node) node.textContent = text;
}

async function fetchMetric(path, params = '') {
  const url = METRICS_API + path + (params ? '?' + params : '');
  const res = await fetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status + ' ' + path);
  return res.json();
}

/* ---------------- Streaming lifecycle ---------------- */

async function streamingLoad() {
  streamState.inFlight++;
  const token = streamState.inFlight;
  try {
    const [w, t, v, a] = await Promise.all([
      fetchMetric('/windows'),
      fetchMetric('/throughput', `withFill=${streamState.withFill}&minutes=60`),
      fetchMetric('/velocity', 'minutes=30'),
      fetchMetric('/anomaly', 'minutes=60'),
    ]);
    if (token !== streamState.inFlight) return;
    updateTiles(w.data, w.elapsedMs);
    updateThroughput(t.data, t.elapsedMs, t.withFill);
    updateVelocity(v.data, v.elapsedMs);
    updateAnomaly(a.data, a.elapsedMs);

    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    setText('streamLastTick',
      `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`);
  } catch (e) {
    console.error('[streaming]', e);
    setText('streamLastTick', '⚠ ' + (e.message || 'error'));
  }
}

function streamingActivate() {
  streamState.active = true;
  initCharts();
  // resize charts on first show (container size changes from hidden)
  requestAnimationFrame(() => {
    Object.values(charts).forEach(c => c && c.resize());
  });
  streamingLoad();
  clearTimeout(streamState.timer);
  const tick = () => {
    if (!streamState.active) return;
    if (document.visibilityState === 'visible') streamingLoad();
    streamState.timer = setTimeout(tick, STREAM_REFRESH_MS);
  };
  streamState.timer = setTimeout(tick, STREAM_REFRESH_MS);
}

function streamingDeactivate() {
  streamState.active = false;
  clearTimeout(streamState.timer);
  streamState.timer = null;
}

/* ---------------- WITH FILL toggle ---------------- */

document.addEventListener('DOMContentLoaded', () => {
  const toggle = document.getElementById('withFillToggle');
  if (toggle) {
    toggle.addEventListener('change', () => {
      streamState.withFill = toggle.checked;
      if (streamState.active) streamingLoad();
    });
  }
});

/* =================================================================== */
/* FUNNEL DASHBOARD — event sequence patterns                            */
/* =================================================================== */

const FUNNEL_REFRESH_MS = 5000;
const funnelCharts = { funnel: null, histogram: null };
const funnelState = {
  active: false,
  timer: null,
  inFlight: 0,
  prev: { stats: {} },
};

const STAGE_COLORS = {
  PLACED:    '#9aa3b2',
  PAID:      '#ffb000',
  SHIPPED:   '#6fbdff',
  DELIVERED: '#5ee99d',
};

function initFunnelCharts() {
  if (typeof echarts === 'undefined') return;
  ['Funnel', 'Histogram'].forEach(name => {
    const id = 'chart' + name;
    const key = name.toLowerCase();
    if (funnelCharts[key]) return;
    const el = document.getElementById(id);
    if (!el) return;
    funnelCharts[key] = echarts.init(el, null, { renderer: 'canvas' });
  });
  if (!window.__funnelResizeBound) {
    window.addEventListener('resize', () => {
      Object.values(funnelCharts).forEach(c => c && c.resize());
    });
    window.__funnelResizeBound = true;
  }
}

function renderFunnel(stages, stats) {
  if (!funnelCharts.funnel) return;

  // ECharts funnel needs descending sort preserved by sort: 'none'
  const data = stages.map(s => ({
    value: Number(s.reached) || 0,
    name: s.stage,
    itemStyle: { color: STAGE_COLORS[s.stage] || '#888', borderColor: 'transparent' },
  }));

  const total = Number(stats.total_orders) || 1;

  const opt = {
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'JetBrains Mono', fontSize: 11, color: '#a4a4ad' },
    tooltip: {
      trigger: 'item',
      backgroundColor: '#0e0e13',
      borderColor: '#3d3d4e',
      textStyle: { color: '#fafafa', fontFamily: 'JetBrains Mono', fontSize: 11 },
      formatter: (p) => {
        const reached = p.value;
        const stage = stages[p.dataIndex];
        const prevReached = p.dataIndex > 0 ? Number(stages[p.dataIndex - 1].reached) : reached;
        const dropOff = prevReached - reached;
        const dropPct = prevReached > 0 ? (dropOff * 100 / prevReached).toFixed(1) : '0';
        const overallPct = total > 0 ? (reached * 100 / total).toFixed(1) : '0';
        return `<div style="color:#fafafa;font-weight:700;margin-bottom:6px">${p.name}</div>`
          + `<div><span style="color:#a4a4ad">Reached</span> · <span style="color:#fafafa;font-weight:700">${fmtCountShort(reached)}</span></div>`
          + `<div><span style="color:#a4a4ad">% of total</span> · <span style="color:#fafafa">${overallPct}%</span></div>`
          + (p.dataIndex > 0
              ? `<div><span style="color:#a4a4ad">Drop-off from prev</span> · <span style="color:#ff6b6b">${fmtCountShort(dropOff)} (-${dropPct}%)</span></div>`
              : '');
      },
    },
    series: [{
      type: 'funnel',
      sort: 'none',
      orient: 'horizontal',
      funnelAlign: 'center',
      left: '5%',
      right: '5%',
      top: 20,
      bottom: 20,
      gap: 4,
      minSize: '20%',
      label: {
        show: true,
        position: 'inside',
        color: '#0a0a0b',
        fontWeight: 700,
        fontFamily: 'JetBrains Mono',
        fontSize: 12,
        formatter: (p) => {
          const overallPct = total > 0 ? (p.value * 100 / total).toFixed(0) : '0';
          return `${p.name}\n${fmtCountShort(p.value)} · ${overallPct}%`;
        },
      },
      labelLine: { show: false },
      data,
    }],
  };
  funnelCharts.funnel.setOption(opt, { notMerge: true });
}

function renderHistogram(rows) {
  if (!funnelCharts.histogram) return;

  if (!rows || rows.length === 0) {
    funnelCharts.histogram.setOption({
      backgroundColor: 'transparent',
      title: {
        text: 'NO DATA · belum ada order PLACED → DELIVERED',
        textStyle: { color: '#6c6c78', fontFamily: 'JetBrains Mono', fontSize: 11, fontWeight: 600 },
        left: 'center', top: 'middle',
      },
      series: [],
    }, { notMerge: true });
    return;
  }

  const data = rows.map(r => {
    const lower = Number(r.lower) || 0;
    const upper = Number(r.upper) || 0;
    const freq = Number(r.frequency) || 0;
    return {
      value: [(lower + upper) / 2, freq],
      lower, upper, freq,
    };
  });

  const opt = {
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'JetBrains Mono', fontSize: 11, color: '#a4a4ad' },
    animation: false,
    grid: { left: 50, right: 24, top: 14, bottom: 36, containLabel: true },
    xAxis: {
      type: 'value',
      name: 'detik',
      nameTextStyle: { color: '#6c6c78', fontSize: 9 },
      nameGap: 22, nameLocation: 'middle',
      axisLine: { lineStyle: { color: '#2a2a36' } },
      axisLabel: { color: '#6c6c78', fontSize: 10 },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      name: 'orders',
      nameTextStyle: { color: '#6c6c78', fontSize: 9 },
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#6c6c78', fontSize: 10 },
      splitLine: { lineStyle: { color: '#1a1a23', type: 'dashed' } },
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#0e0e13',
      borderColor: '#3d3d4e',
      textStyle: { color: '#fafafa', fontFamily: 'JetBrains Mono', fontSize: 11 },
      formatter: (params) => {
        const d = params[0].data;
        return `<div style="color:#fafafa;font-weight:700;margin-bottom:4px">`
          + `${d.lower.toFixed(1)}s – ${d.upper.toFixed(1)}s</div>`
          + `<div><span style="color:#a4a4ad">orders</span> · <span style="color:#fafafa;font-weight:700">${fmtCountShort(d.freq)}</span></div>`;
      },
    },
    series: [{
      type: 'bar',
      barWidth: '85%',
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#ff7ad9' },
          { offset: 1, color: 'rgba(255, 122, 217, 0.4)' },
        ]),
      },
      data,
    }],
  };
  funnelCharts.histogram.setOption(opt, { notMerge: true });
}

function renderFunnelTiles(stats) {
  const map = {
    'tile-total':     fmtCountShort(stats.total_orders),
    'tile-completed': fmtCountShort(stats.completed),
    'tile-conv-pct':  (Number(stats.conversion_pct) || 0).toFixed(1) + '%',
    'tile-cancelled': fmtCountShort(stats.ever_cancelled),
  };
  for (const [id, val] of Object.entries(map)) {
    const node = document.getElementById(id);
    if (!node) continue;
    if (node.textContent !== val) {
      node.textContent = val;
      flashTile(id);
    }
  }
}

function renderPatternTable(stats) {
  const fields = ['stuck_at_placed', 'abandoned_at_placed', 'cancelled_after_paid',
                  'shipped_without_paid', 'completed'];
  for (const f of fields) {
    const node = document.querySelector(`[data-pattern="${f}"]`);
    if (!node) continue;
    const val = fmtCountShort(stats[f] || 0);
    if (node.textContent !== val) {
      node.textContent = val;
      node.classList.remove('flash');
      void node.offsetWidth;
      node.classList.add('flash');
    }
  }
}

async function funnelLoad() {
  funnelState.inFlight++;
  const token = funnelState.inFlight;
  try {
    const [f, h] = await Promise.all([
      fetchMetric('/funnel'),
      fetchMetric('/conversion-histogram', 'bins=20'),
    ]);
    if (token !== funnelState.inFlight) return;

    const stages = f.data.stages || [];
    const stats = f.data.stats || {};

    renderFunnelTiles(stats);
    renderFunnel(stages, stats);
    renderPatternTable(stats);
    renderHistogram(h.data || []);

    setText('funnelElapsed', f.elapsedMs + ' ms');
    setText('histogramElapsed', h.elapsedMs + ' ms');

    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    setText('funnelLastTick',
      `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`);
  } catch (e) {
    console.error('[funnel]', e);
    setText('funnelLastTick', '⚠ ' + (e.message || 'error'));
  }
}

function funnelActivate() {
  funnelState.active = true;
  initFunnelCharts();
  requestAnimationFrame(() => {
    Object.values(funnelCharts).forEach(c => c && c.resize());
  });
  funnelLoad();
  clearTimeout(funnelState.timer);
  const tick = () => {
    if (!funnelState.active) return;
    if (document.visibilityState === 'visible') funnelLoad();
    funnelState.timer = setTimeout(tick, FUNNEL_REFRESH_MS);
  };
  funnelState.timer = setTimeout(tick, FUNNEL_REFRESH_MS);
}

function funnelDeactivate() {
  funnelState.active = false;
  clearTimeout(funnelState.timer);
  funnelState.timer = null;
}

/* Add keyboard shortcuts for dashboard tabs */
document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') return;
  if (e.key === '4') location.hash = 'streaming';
  if (e.key === '5') location.hash = 'funnel';
  if (e.key === '6') location.hash = 'distribution';
});

/* =================================================================== */
/* DISTRIBUTION DASHBOARD — quantiles, lag, top-K, HLL                   */
/* =================================================================== */

const DIST_REFRESH_MS = 3000;
const distCharts = { quantiles: null };
const distState = {
  active: false,
  timer: null,
  inFlight: 0,
};

function initDistCharts() {
  if (typeof echarts === 'undefined') return;
  if (!distCharts.quantiles) {
    const el = document.getElementById('chartQuantiles');
    if (el) distCharts.quantiles = echarts.init(el, null, { renderer: 'canvas' });
  }
  if (!window.__distResizeBound) {
    window.addEventListener('resize', () => {
      Object.values(distCharts).forEach(c => c && c.resize());
    });
    window.__distResizeBound = true;
  }
}

function renderCardinalityTiles(card) {
  const exact = card.exact || {};
  const hll = card.hll || {};
  const setTile = (id, val) => {
    const node = document.getElementById(id);
    if (!node) return;
    if (node.textContent !== val) {
      node.textContent = val;
      flashTile(id);
    }
  };
  setTile('tile-card-exact-cust', fmtCountShort(exact.customers));
  setTile('tile-card-hll-cust',   fmtCountShort(hll.customers));
  setTile('tile-card-exact-ord',  fmtCountShort(exact.orders));
  setTile('tile-card-hll-ord',    fmtCountShort(hll.orders));
  setText('tile-card-exact-ms', card.exactMs + ' ms');
  setText('tile-card-hll-ms',   card.hllMs + ' ms');
  const speedup = (card.speedup || 1).toFixed(1);
  setText('cardSpeedup', speedup + '×');

  // error vs exact (delta percent)
  if (exact.customers && hll.customers) {
    const e = Number(exact.customers);
    const h = Number(hll.customers);
    const errPct = e > 0 ? Math.abs(e - h) * 100 / e : 0;
    setText('tile-card-error', 'err ' + errPct.toFixed(2) + '% · ~0.81% theoretical');
  }
}

function renderQuantiles(rows, elapsedMs) {
  if (!distCharts.quantiles) return;

  const p50 = rows.map(r => [r.minute, Number(r.p50) || 0]);
  const p95 = rows.map(r => [r.minute, Number(r.p95) || 0]);
  const p99 = rows.map(r => [r.minute, Number(r.p99) || 0]);

  const opt = baseChartOption();
  opt.legend = {
    data: ['p50', 'p95', 'p99'],
    textStyle: { color: '#a4a4ad', fontSize: 10 },
    right: 12, top: 0,
    itemWidth: 14, itemHeight: 2,
  };
  opt.grid.top = 30;
  opt.yAxis = {
    type: 'value',
    name: 'Rp',
    nameTextStyle: { color: '#6c6c78', fontSize: 9 },
    axisLine: { show: false }, axisTick: { show: false },
    axisLabel: {
      color: '#6c6c78', fontSize: 10,
      formatter: (v) => v >= 1_000_000 ? (v / 1_000_000).toFixed(0) + 'M' : (v / 1000).toFixed(0) + 'K',
    },
    splitLine: { lineStyle: { color: '#1a1a23', type: 'dashed' } },
  };
  opt.tooltip.formatter = (params) => {
    if (!params.length) return '';
    const ts = new Date(params[0].value[0]);
    const pad = (n) => String(n).padStart(2, '0');
    let html = `<div style="color:#fafafa;font-weight:700;margin-bottom:6px">`
      + `${pad(ts.getHours())}:${pad(ts.getMinutes())}</div>`;
    for (const p of params) {
      html += `<div><span style="display:inline-block;width:8px;height:2px;background:${p.color};vertical-align:middle;margin-right:6px"></span>`
        + `<span style="color:#a4a4ad">${p.seriesName}</span> · `
        + `<span style="color:#fafafa">${fmtMoneyShort(p.value[1])}</span></div>`;
    }
    return html;
  };
  opt.series = [
    {
      name: 'p50', type: 'line', smooth: 0.25, symbol: 'none',
      lineStyle: { color: '#6fbdff', width: 1.6 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(111, 189, 255, 0.18)' },
          { offset: 1, color: 'rgba(111, 189, 255, 0)' },
        ]),
      },
      data: p50,
    },
    {
      name: 'p95', type: 'line', smooth: 0.25, symbol: 'none',
      lineStyle: { color: '#ffb000', width: 1.6 },
      data: p95,
    },
    {
      name: 'p99', type: 'line', smooth: 0.25, symbol: 'none',
      lineStyle: { color: '#ff6b6b', width: 1.8 },
      data: p99,
    },
  ];
  distCharts.quantiles.setOption(opt, { notMerge: true });
  setText('quantilesElapsed', elapsedMs + ' ms');
}

function renderPeriodOverPeriod(rows, elapsedMs) {
  const tbody = document.querySelector('#popTable tbody');
  if (!tbody) return;

  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:#6c6c78;padding:24px">no data</td></tr>';
    setText('popElapsed', elapsedMs + ' ms');
    return;
  }

  tbody.innerHTML = rows.map((r, idx) => {
    const delta = Number(r.delta_orders) || 0;
    const pct = r.pct_change;
    const pctNum = pct != null && pct !== '' ? Number(pct) : null;
    const minute = new Date(r.minute);
    const pad = (n) => String(n).padStart(2, '0');
    const mLabel = `${pad(minute.getHours())}:${pad(minute.getMinutes())}`;
    const deltaCls = delta > 0 ? 'delta-up' : delta < 0 ? 'delta-down' : 'delta-zero';
    const deltaStr = (delta > 0 ? '+' : '') + delta;
    let pctStr = '—';
    let pctCls = 'delta-zero';
    if (pctNum != null && isFinite(pctNum)) {
      pctStr = (pctNum > 0 ? '+' : '') + pctNum.toFixed(1) + '%';
      pctCls = pctNum > 0 ? 'delta-up' : pctNum < 0 ? 'delta-down' : 'delta-zero';
    }
    return `<tr style="animation-delay:${Math.min(idx * 6, 120)}ms">`
      + `<td class="minute-col">${mLabel}</td>`
      + `<td class="num">${fmtCountShort(r.orders)}</td>`
      + `<td class="num ${deltaCls}">${deltaStr}</td>`
      + `<td class="num">${fmtMoneyShort(r.revenue)}</td>`
      + `<td class="num ${pctCls}">${pctStr}</td>`
      + `</tr>`;
  }).join('');
  setText('popElapsed', elapsedMs + ' ms');
}

function renderTopK(rows, elapsedMs) {
  const tbody = document.querySelector('#topkTable tbody');
  if (!tbody) return;

  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="3" style="text-align:center;color:#6c6c78;padding:24px">no data</td></tr>';
    setText('topkElapsed', elapsedMs + ' ms');
    return;
  }

  tbody.innerHTML = rows.map((r, idx) => {
    const minute = new Date(r.minute);
    const pad = (n) => String(n).padStart(2, '0');
    const mLabel = `${pad(minute.getHours())}:${pad(minute.getMinutes())}`;
    // top3_products is an array (Java JDBC may return List or stringified)
    let arr = r.top3_products;
    if (typeof arr === 'string') {
      // strip leading/trailing brackets and parse: "['a','b','c']" -> ['a','b','c']
      try {
        arr = arr.replace(/^\[|\]$/g, '').split(',').map(s => s.trim().replace(/^['"]|['"]$/g, ''));
      } catch { arr = []; }
    }
    if (!Array.isArray(arr)) arr = [];
    const chips = arr.slice(0, 3).map((name, i) =>
      `<span class="top3-chip"><span class="top3-chip-rank">#${i + 1}</span>${escapeHtml(name)}</span>`
    ).join('');
    return `<tr style="animation-delay:${Math.min(idx * 6, 120)}ms">`
      + `<td class="minute-col">${mLabel}</td>`
      + `<td><div class="top3-chips">${chips}</div></td>`
      + `<td class="num">${fmtCountShort(r.order_count)}</td>`
      + `</tr>`;
  }).join('');
  setText('topkElapsed', elapsedMs + ' ms');
}

async function distributionLoad() {
  distState.inFlight++;
  const token = distState.inFlight;
  try {
    const [c, q, p, t] = await Promise.all([
      fetchMetric('/cardinality'),
      fetchMetric('/quantiles', 'minutes=60'),
      fetchMetric('/period-over-period', 'minutes=60'),
      fetchMetric('/top-per-bucket', 'minutes=15'),
    ]);
    if (token !== distState.inFlight) return;

    renderCardinalityTiles(c.data || {});
    renderQuantiles(q.data || [], q.elapsedMs);
    renderPeriodOverPeriod(p.data || [], p.elapsedMs);
    renderTopK(t.data || [], t.elapsedMs);

    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    setText('distLastTick',
      `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`);
  } catch (e) {
    console.error('[distribution]', e);
    setText('distLastTick', '⚠ ' + (e.message || 'error'));
  }
}

function distributionActivate() {
  distState.active = true;
  initDistCharts();
  requestAnimationFrame(() => {
    Object.values(distCharts).forEach(c => c && c.resize());
  });
  distributionLoad();
  clearTimeout(distState.timer);
  const tick = () => {
    if (!distState.active) return;
    if (document.visibilityState === 'visible') distributionLoad();
    distState.timer = setTimeout(tick, DIST_REFRESH_MS);
  };
  distState.timer = setTimeout(tick, DIST_REFRESH_MS);
}

function distributionDeactivate() {
  distState.active = false;
  clearTimeout(distState.timer);
  distState.timer = null;
}
