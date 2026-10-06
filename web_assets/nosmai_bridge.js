"use strict";
(() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
  var __esm = (fn, res) => function __init() {
    return fn && (res = (0, fn[__getOwnPropNames(fn)[0]])(fn = 0)), res;
  };
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __publicField = (obj, key, value) => __defNormalProp(obj, typeof key !== "symbol" ? key + "" : key, value);

  // node_modules/@nosmai/web-sdk/dist/errors.js
  function s(a4) {
    const e2 = a4?.name;
    return e2 === "NotAllowedError" || e2 === "SecurityError" ? new r({ type: "cameraPermissionDenied", message: "camera permission denied", cause: a4 }) : e2 === "NotFoundError" || e2 === "OverconstrainedError" ? new r({ type: "cameraUnavailable", message: "no camera matched the request", cause: a4 }) : e2 === "NotReadableError" ? new r({ type: "cameraUnavailable", message: "the camera is in use by another application", cause: a4 }) : new r({ type: "cameraUnavailable", message: a4 instanceof Error ? a4.message : String(a4), cause: a4 });
  }
  var r;
  var init_errors = __esm({
    "node_modules/@nosmai/web-sdk/dist/errors.js"() {
      r = class extends Error {
        constructor(e2) {
          super(e2.message);
          __publicField(this, "type");
          __publicField(this, "code");
          __publicField(this, "details");
          __publicField(this, "cause");
          this.name = "NosmaiError", this.type = e2.type, this.code = e2.code ?? e2.type, this.details = e2.details, this.cause = e2.cause;
        }
        get isRecoverable() {
          switch (this.type) {
            case "networkError":
            case "cameraUnavailable":
            case "effectLoadFailed":
            case "recordingWriteFailed":
              return true;
            default:
              return false;
          }
        }
        get userMessage() {
          switch (this.type) {
            case "invalidLicense":
              return "This Nosmai licence is not valid.";
            case "licenseExpired":
              return "This Nosmai licence has expired.";
            case "cameraPermissionDenied":
              return "Camera access was blocked.";
            case "cameraUnavailable":
              return "No camera is available, or another app is using it.";
            case "webglUnavailable":
              return "This browser cannot run Nosmai.";
            case "networkError":
              return "Could not reach the Nosmai licence service.";
            case "effectNotFound":
            case "effectInvalidFormat":
            case "effectLoadFailed":
              return "That effect could not be loaded.";
            case "sdkNotInitialized":
              return "Nosmai has not been started yet.";
            default:
              return "Something went wrong with Nosmai.";
          }
        }
        get recoveryActions() {
          switch (this.type) {
            case "cameraPermissionDenied":
              return ["Allow camera access for this site", "Reload the page"];
            case "cameraUnavailable":
              return ["Close other apps or tabs using the camera", "Check that a camera is connected"];
            case "webglUnavailable":
              return ["Update your browser", "Enable hardware acceleration"];
            case "networkError":
              return ["Check your connection", "Try again"];
            case "invalidLicense":
            case "licenseExpired":
              return ["Check your API key", "Contact Nosmai to renew"];
            default:
              return [];
          }
        }
      };
    }
  });

  // node_modules/@nosmai/web-sdk/dist/effect-cache.js
  var effect_cache_exports = {};
  __export(effect_cache_exports, {
    cachedEffectIds: () => h4,
    clearEffectCache: () => s3,
    isEffectCached: () => u4,
    readCachedEffect: () => o2,
    writeCachedEffect: () => f3
  });
  function a3(e2) {
    return `https://nosmai.invalid/effect/${encodeURIComponent(e2)}`;
  }
  async function c3() {
    try {
      return typeof caches > "u" ? null : await caches.open(i2);
    } catch {
      return null;
    }
  }
  async function o2(e2) {
    try {
      const t = await c3();
      if (!t) return null;
      const n = await t.match(a3(e2));
      if (!n) return null;
      const r3 = await n.arrayBuffer();
      return r3.byteLength === 0 ? (await t.delete(a3(e2)).catch(() => {
      }), null) : new Uint8Array(r3);
    } catch {
      return null;
    }
  }
  async function f3(e2, t) {
    try {
      const n = await c3();
      if (!n || t.byteLength === 0) return false;
      const r3 = t.slice().buffer;
      return await n.put(a3(e2), new Response(r3, { headers: { "Content-Type": "application/octet-stream", "Content-Length": String(t.byteLength) } })), true;
    } catch {
      return false;
    }
  }
  async function u4(e2) {
    try {
      const t = await c3();
      return t ? !!await t.match(a3(e2)) : false;
    } catch {
      return false;
    }
  }
  async function s3(e2) {
    try {
      if (e2 === void 0) {
        typeof caches < "u" && await caches.delete(i2);
        return;
      }
      await (await c3())?.delete(a3(e2));
    } catch {
    }
  }
  async function h4() {
    try {
      const e2 = await c3();
      return e2 ? (await e2.keys()).map((n) => n.url.split("/effect/")[1]).filter(Boolean).map((n) => decodeURIComponent(n)) : [];
    } catch {
      return [];
    }
  }
  var i2;
  var init_effect_cache = __esm({
    "node_modules/@nosmai/web-sdk/dist/effect-cache.js"() {
      i2 = "nosmai-effects-v1";
    }
  });

  // node_modules/@nosmai/web-sdk/dist/cloud.js
  var cloud_exports = {};
  __export(cloud_exports, {
    DEFAULT_CLOUD_BASE: () => p3,
    downloadCloudEffect: () => v2,
    listCloudEffects: () => C
  });
  function U(e2) {
    const t = e2?.category, a4 = String((t && typeof t == "object" ? t.slug ?? t.name : t) ?? e2?.filterCategory ?? "").toLowerCase();
    return E2[a4] ?? "effect";
  }
  function T2(e2) {
    const t = typeof e2.priceCents == "number" ? e2.priceCents : 0;
    return { id: String(e2.filterId ?? e2.id ?? e2.backendId ?? ""), name: String(e2.name ?? ""), displayName: String(e2.displayName ?? e2.name ?? ""), category: U(e2), source: "cloud", previewUrl: e2.filterPreview ?? e2.thumbnailUrl ?? e2.previewUrl ?? void 0, fileSize: typeof e2.fileSize == "number" ? e2.fileSize : void 0, isFree: e2.isFree !== void 0 ? e2.isFree !== false : t === 0, isDownloaded: false };
  }
  async function h5(e2, t, a4 = 3) {
    let r3;
    for (let n = 0; n < a4; n++) try {
      return await fetch(e2, t);
    } catch (l4) {
      if (r3 = l4, t.signal?.aborted) break;
      n < a4 - 1 && await new Promise((o3) => setTimeout(o3, 250 * (n + 1)));
    }
    const s4 = r3 instanceof Error ? r3.message : String(r3);
    throw new r({ type: "networkError", code: "CLOUD_UNREACHABLE", message: `could not reach the cloud filter service after ${a4} attempts: ${s4}. This is a transport failure, not a licence problem.`, cause: r3 instanceof Error ? r3 : void 0 });
  }
  async function C(e2) {
    const t = (e2.baseUrl ?? p3).replace(/\/$/, ""), a4 = new URL(`${t}/cloud-filters`);
    if (e2.category) {
      const i3 = Object.entries(E2).find(([, u5]) => u5 === e2.category)?.[0];
      i3 && a4.searchParams.set("filterType", i3);
    }
    e2.page && a4.searchParams.set("page", String(e2.page)), e2.limit && a4.searchParams.set("limit", String(e2.limit));
    const r3 = await h5(a4, { headers: { Authorization: `Bearer ${e2.apiKey}` }, signal: e2.signal });
    if (!r3.ok) throw new r({ type: r3.status === 401 ? "invalidLicense" : "networkError", code: `HTTP_${r3.status}`, message: `cloud filters: HTTP ${r3.status}` });
    const s4 = await r3.json(), n = s4?.data?.cloudFilters ?? s4?.data ?? {}, l4 = [...n.filters ?? [], ...n.free ?? [], ...n.purchased ?? []], o3 = /* @__PURE__ */ new Set(), d3 = l4.map(T2).filter((i3) => !i3.id || o3.has(i3.id) ? false : (o3.add(i3.id), true)), f4 = n.pagination ?? {};
    return { items: d3, page: f4.currentPage ?? 1, totalPages: f4.totalPages ?? 1, total: f4.totalFilters ?? d3.length, hasNextPage: !!f4.hasNextPage };
  }
  async function v2(e2, t) {
    const a4 = await o2(e2);
    if (a4) return t.onProgress?.(1), a4;
    const r3 = (t.baseUrl ?? p3).replace(/\/$/, ""), s4 = await h5(`${r3}/filters/download?filterId=${encodeURIComponent(e2)}`, { headers: { Authorization: `Bearer ${t.apiKey}` }, signal: t.signal });
    if (!s4.ok) throw new r({ type: "effectNotFound", code: `HTTP_${s4.status}`, message: `could not resolve ${e2}: HTTP ${s4.status}` });
    const n = await s4.json(), l4 = n?.data?.downloadUrl ?? n?.downloadUrl;
    if (!l4) throw new r({ type: "effectNotFound", message: `no download URL for ${e2}` });
    const o3 = await h5(l4, { signal: t.signal });
    if (!o3.ok || !o3.body) throw new r({ type: "effectLoadFailed", message: `download failed: HTTP ${o3.status}` });
    const d3 = Number(o3.headers.get("content-length") ?? 0);
    if (!d3 || !t.onProgress) {
      const c4 = new Uint8Array(await o3.arrayBuffer());
      return f3(e2, c4), c4;
    }
    const f4 = o3.body.getReader(), i3 = [];
    let u5 = 0;
    for (; ; ) {
      const { done: c4, value: w3 } = await f4.read();
      if (c4) break;
      i3.push(w3), u5 += w3.length, t.onProgress(Math.min(1, u5 / d3));
    }
    const m = new Uint8Array(u5);
    let y2 = 0;
    for (const c4 of i3) m.set(c4, y2), y2 += c4.length;
    return f3(e2, m), m;
  }
  var p3, E2;
  var init_cloud = __esm({
    "node_modules/@nosmai/web-sdk/dist/cloud.js"() {
      init_errors();
      init_effect_cache();
      p3 = "https://cloudfilters-api.nosmai.com/api/v1";
      E2 = { effects: "effect", filter: "filter", bg: "background", beauty_effect: "beautyEffect", "color-grading": "filter", "interactive-backgrounds": "background", "beauty-effects": "beautyEffect" };
    }
  });

  // node_modules/@nosmai/web-sdk/dist/engine.js
  init_errors();

  // node_modules/@nosmai/web-sdk/dist/recorder.js
  init_errors();
  var d = ["video/mp4;codecs=avc1.42E01E,mp4a.40.2", "video/mp4", "video/webm;codecs=vp9,opus", "video/webm;codecs=vp8,opus", "video/webm"];
  function u(a4) {
    if (typeof MediaRecorder > "u") return null;
    for (const e2 of a4) try {
      if (MediaRecorder.isTypeSupported(e2)) return e2;
    } catch {
    }
    return null;
  }
  function p() {
    return typeof MediaRecorder < "u" && typeof HTMLCanvasElement < "u" && typeof HTMLCanvasElement.prototype.captureStream == "function";
  }
  var f = class {
    constructor(e2, s4) {
      __publicField(this, "mirror");
      __publicField(this, "onProgress");
      __publicField(this, "recorder", null);
      __publicField(this, "chunks", []);
      __publicField(this, "startedAt", 0);
      __publicField(this, "mimeType", "");
      __publicField(this, "canvasStream", null);
      __publicField(this, "progressTimer", null);
      this.mirror = e2, this.onProgress = s4;
    }
    get isRecording() {
      return this.recorder !== null && this.recorder.state === "recording";
    }
    get duration() {
      return this.isRecording ? (performance.now() - this.startedAt) / 1e3 : 0;
    }
    start(e2, s4, t = {}) {
      if (this.isRecording) throw new r({ type: "recordingInProgress", message: "already recording \u2014 stop the current recording first" });
      if (!p()) throw new r({ type: "platformError", code: "RECORDING_UNSUPPORTED", message: "this browser has no MediaRecorder or canvas.captureStream" });
      const o3 = u(t.mimeTypes ?? d);
      if (!o3) throw new r({ type: "platformError", code: "NO_SUPPORTED_CONTAINER", message: "no supported video container for MediaRecorder" });
      const r3 = this.mirror.acquire(e2);
      if (this.canvasStream = r3, (t.audio ?? true) && s4) for (const i3 of s4.getAudioTracks()) r3.addTrack(i3);
      const c4 = new MediaRecorder(r3, { mimeType: o3, ...t.videoBitsPerSecond ? { videoBitsPerSecond: t.videoBitsPerSecond } : {} });
      this.chunks = [], c4.ondataavailable = (i3) => {
        i3.data && i3.data.size > 0 && this.chunks.push(i3.data);
      }, c4.start(1e3), this.recorder = c4, this.mimeType = o3, this.startedAt = performance.now(), this.onProgress && (this.progressTimer = setInterval(() => {
        this.isRecording && this.onProgress(this.duration);
      }, 250));
    }
    frame() {
      this.mirror.frame();
    }
    async stop() {
      const e2 = this.recorder;
      if (!e2 || e2.state === "inactive") return { success: false, duration: 0, fileSize: 0, error: "not currently recording" };
      const s4 = this.duration, t = await new Promise((o3) => {
        e2.onstop = () => o3(new Blob(this.chunks, { type: this.mimeType }));
        try {
          e2.stop();
        } catch {
        }
      });
      return this.cleanup(), t.size === 0 ? { success: false, duration: s4, fileSize: 0, mimeType: this.mimeType, error: "the recording produced no data \u2014 was the canvas rendering?" } : { success: true, blob: t, duration: s4, fileSize: t.size, mimeType: this.mimeType };
    }
    cancel() {
      try {
        this.recorder?.stop();
      } catch {
      }
      this.cleanup(), this.chunks = [];
    }
    cleanup() {
      this.progressTimer !== null && (clearInterval(this.progressTimer), this.progressTimer = null), this.canvasStream && (this.mirror.release(), this.canvasStream = null), this.recorder = null;
    }
  };
  function l(a4, e2 = "image/png", s4) {
    return new Promise((t, o3) => {
      try {
        a4.toBlob((r3) => r3 ? t(r3) : o3(new r({ type: "platformError", code: "CAPTURE_FAILED", message: "the canvas produced no image data" })), e2, s4);
      } catch (r3) {
        o3(new r({ type: "platformError", code: "CAPTURE_FAILED", message: r3 instanceof Error ? r3.message : String(r3), cause: r3 }));
      }
    });
  }

  // node_modules/@nosmai/web-sdk/dist/canvas-mirror.js
  var h = "onmessage = (e) => { setTimeout(() => postMessage(0), e.data); };";
  function a(s4, t) {
    let r3 = setTimeout(function e2() {
      r3 = setTimeout(e2, s4), t();
    }, s4);
    return { stop() {
      clearTimeout(r3);
    } };
  }
  function o(s4, t) {
    let r3 = a(s4, t), e2;
    try {
      const i3 = URL.createObjectURL(new Blob([h], { type: "text/javascript" }));
      e2 = new Worker(i3), URL.revokeObjectURL(i3);
    } catch {
      return r3;
    }
    return e2.onmessage = () => {
      r3?.stop(), r3 = void 0;
      try {
        t();
      } finally {
        e2.postMessage(s4);
      }
    }, e2.onerror = () => {
      e2.terminate(), r3 ?? (r3 = a(s4, t));
    }, e2.postMessage(s4), { stop() {
      e2.terminate(), r3?.stop();
    } };
  }
  var c = class {
    constructor() {
      __publicField(this, "mirror", null);
      __publicField(this, "ctx", null);
      __publicField(this, "source", null);
      __publicField(this, "stream", null);
      __publicField(this, "ticker", null);
      __publicField(this, "lastFrameAt", 0);
      __publicField(this, "refs", 0);
    }
    get active() {
      return this.stream !== null;
    }
    acquire(t) {
      if (this.refs += 1, this.stream) return this.stream;
      const r3 = document.createElement("canvas");
      r3.width = Math.max(1, t.width), r3.height = Math.max(1, t.height);
      const e2 = r3.getContext("2d", { alpha: false, desynchronized: true });
      if (!e2) throw this.refs -= 1, new Error("could not create the 2D context the mirror needs");
      this.mirror = r3, this.ctx = e2, this.source = t;
      try {
        e2.drawImage(t, 0, 0);
      } catch {
      }
      this.stream = r3.captureStream(0);
      for (const i3 of this.stream.getVideoTracks()) try {
        i3.contentHint = "motion";
      } catch {
      }
      return this.ticker = o(250, () => {
        performance.now() - this.lastFrameAt > 250 && this.blit();
      }), this.stream;
    }
    release() {
      if (this.refs = Math.max(0, this.refs - 1), !(this.refs > 0)) {
        if (this.ticker?.stop(), this.ticker = null, this.stream) {
          for (const t of this.stream.getVideoTracks()) t.stop();
          this.stream = null;
        }
        this.mirror = null, this.ctx = null, this.source = null;
      }
    }
    frame() {
      this.stream && (this.lastFrameAt = performance.now(), this.blit());
    }
    blit() {
      const t = this.source, r3 = this.ctx, e2 = this.mirror;
      if (!this.stream || !t || !r3 || !e2 || t.width === 0 || t.height === 0) return;
      (e2.width !== t.width || e2.height !== t.height) && (e2.width = t.width, e2.height = t.height);
      try {
        r3.drawImage(t, 0, 0);
      } catch {
        return;
      }
      const [i3] = this.stream.getVideoTracks();
      i3.requestFrame?.();
    }
  };

  // node_modules/@nosmai/web-sdk/dist/render-scale.js
  var i = [1, 0.85, 0.7, 0.55, 0.45];
  var h2 = 24;
  var l2 = 45;
  var r2 = 3;
  var u2 = class {
    constructor(t) {
      __publicField(this, "apply");
      __publicField(this, "current", 1);
      __publicField(this, "slow", 0);
      __publicField(this, "fast", 0);
      __publicField(this, "pinned", null);
      this.apply = t;
    }
    get scale() {
      return this.current;
    }
    pin(t) {
      this.pinned = t, this.slow = 0, this.fast = 0, t !== null && (this.current = Math.min(1, Math.max(0.25, t)), this.apply(this.current));
    }
    tick(t, n) {
      if (this.pinned !== null || t <= 0) return;
      const e2 = 1e3 / t;
      if (n === null || n < e2 * 0.33) {
        this.slow = 0;
        return;
      }
      const s4 = i.indexOf(this.current);
      if (!(s4 < 0)) {
        if (t < h2) this.fast = 0, this.slow += 1;
        else if (t > l2) this.slow = 0, this.fast += 1;
        else {
          this.slow = 0, this.fast = 0;
          return;
        }
        if (this.slow >= r2 && s4 < i.length - 1) this.current = i[s4 + 1], this.slow = 0;
        else if (this.fast >= r2 && s4 > 0) this.current = i[s4 - 1], this.fast = 0;
        else return;
        this.apply(this.current);
      }
    }
  };

  // node_modules/@nosmai/web-sdk/dist/engine.js
  var import_meta = {};
  var f2 = { 0: "unverified", 1: "valid", 2: "expired", 3: "invalid" };
  var g = (s4) => f2[s4] ?? "unverified";
  async function b() {
    let s4 = null;
    try {
      s4 = localStorage.getItem("_n_d");
    } catch {
    }
    if (!s4) {
      s4 = crypto.randomUUID();
      try {
        localStorage.setItem("_n_d", s4);
      } catch {
      }
    }
    const e2 = new TextEncoder().encode(`${location.origin}:${s4}`), t = await crypto.subtle.digest("SHA-256", e2);
    return [...new Uint8Array(t)].map((r3) => r3.toString(16).padStart(2, "0")).join("");
  }
  function p2() {
    const s4 = /(Chrome|Firefox|Safari|Edg)\/(\d+)/.exec(navigator.userAgent.replace("Edg", "Edge"));
    return s4 ? `${s4[1].toLowerCase()}-${s4[2]}` : "unknown";
  }
  var c2 = { Locate: 1, Mesh: 2, Expression: 3, Matte: 4, Hair: 5 };
  var y = { [c2.Locate]: "nosmai_face_locate.nmdl", [c2.Mesh]: "nosmai_face_mesh.nmdl", [c2.Expression]: "nosmai_face_expression.nmdl", [c2.Matte]: "nosmai_face_matte.nmdl", [c2.Hair]: "nosmai_hair_matte.nmdl" };
  var w = "0.1.0-alpha.1";
  var T = class {
    constructor(e2) {
      __publicField(this, "module", null);
      __publicField(this, "gl", null);
      __publicField(this, "canvas", null);
      __publicField(this, "cameraTexture", null);
      __publicField(this, "width", 0);
      __publicField(this, "height", 0);
      __publicField(this, "mirrored", true);
      __publicField(this, "running", false);
      __publicField(this, "assetBase");
      __publicField(this, "options");
      __publicField(this, "licence", { status: "unverified", secondsRemaining: 0, watermarked: true, blurred: false });
      __publicField(this, "swapQueue", Promise.resolve());
      __publicField(this, "swapping", false);
      __publicField(this, "frames", 0);
      __publicField(this, "lastSample", 0);
      __publicField(this, "renderFailed", false);
      __publicField(this, "recorder", null);
      __publicField(this, "mirror", new c());
      __publicField(this, "renderMsTotal", 0);
      __publicField(this, "renderMsCount", 0);
      __publicField(this, "scaler", new u2((e2) => {
        this.module?.ccall("nosmai_set_render_scale", null, ["number"], [e2]);
      }));
      this.options = e2, this.canvas = e2.canvas, this.assetBase = (e2.assetBase ?? E()).replace(/\/$/, "");
    }
    asset(e2) {
      return `${this.assetBase}/${e2}`;
    }
    engineDir() {
      const e2 = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 15, 1, 13, 0, 65, 1, 253, 15, 65, 2, 253, 15, 253, 128, 2, 11]);
      let t = false;
      try {
        t = WebAssembly.validate(e2);
      } catch {
      }
      return t ? "engine" : "engine-baseline";
    }
    licenceState() {
      if (!this.module) return this.licence;
      const e2 = this.module.ccall("nosmai_license_status", "number", [], []), t = g(e2);
      return { ...this.licence, status: t, secondsRemaining: this.module.ccall("nosmai_license_seconds_remaining", "number", [], []), watermarked: this.module.ccall("nosmai_license_requires_watermark", "number", [], []) === 1, blurred: this.module.ccall("nosmai_license_requires_blur", "number", [], []) === 1 };
    }
    setEffectParameter(e2, t) {
      return this.module?.ccall("nosmai_effect_set_parameter", "number", ["string", "number"], [e2, t]) === 1;
    }
    setEffectParameterString(e2, t) {
      return this.module?.ccall("nosmai_effect_set_parameter_string", "number", ["string", "string"], [e2, t]) === 1;
    }
    getEffectParameter(e2) {
      return this.module?.ccall("nosmai_effect_get_parameter", "number", ["string"], [e2]) ?? NaN;
    }
    getEffectParameterString(e2) {
      return this.module?.ccall("nosmai_effect_get_parameter_string", "string", ["string"], [e2]) ?? "";
    }
    activeEffectType() {
      return this.module?.ccall("nosmai_effect_active_type", "string", [], []) ?? "";
    }
    effectIsActive() {
      return this.module?.ccall("nosmai_effect_is_active", "number", [], []) === 1;
    }
    gameReady() {
      return this.module?.ccall("nosmai_game_ready", "number", [], []) === 1;
    }
    gameTap(e2, t) {
      return this.module?.ccall("nosmai_game_tap", "number", ["number", "number"], [e2, t]) === 1;
    }
    gameInput(e2, t, r3, n) {
      return this.module?.ccall("nosmai_game_input", "number", ["string", "number", "number", "number"], [e2, t, r3, n]) === 1;
    }
    gamePause() {
      this.module?.ccall("nosmai_game_pause", null, [], []);
    }
    gameResume() {
      this.module?.ccall("nosmai_game_resume", null, [], []);
    }
    gameRestart() {
      this.module?.ccall("nosmai_game_restart", null, [], []);
    }
    drainGameEvents() {
      if (!this.module) return [];
      let e2;
      try {
        e2 = this.module.ccall("nosmai_game_drain_events", "string", [], []);
      } catch {
        return [];
      }
      if (!e2 || e2 === "[]") return [];
      try {
        const t = JSON.parse(e2);
        return Array.isArray(t) ? t : [];
      } catch {
        return [];
      }
    }
    licenceFeature(e2) {
      if (!this.module) return false;
      try {
        return this.module.ccall("nosmai_license_feature_enabled", "number", ["string"], [e2]) === 1;
      } catch {
        return false;
      }
    }
    publishLicence(e2) {
      this.licence = { ...this.licence, ...e2 }, this.options.onLicence?.(this.licenceState());
    }
    async init() {
      if (!this.canvas) throw new Error("no canvas");
      if (!this.canvas.isConnected) throw new Error("the canvas must be in the document before init()");
      const e2 = v(this.canvas), t = this.engineDir(), r3 = this.asset(`${t}/nosmai_engine.js`);
      let n;
      try {
        n = (await import(r3)).default;
      } catch (i3) {
        throw new r({ type: "platformError", message: `could not load the engine from ${r3} \u2014 check assetBase`, cause: i3 });
      }
      const a4 = await n({ print: () => {
      }, printErr: () => {
      }, locateFile: (i3) => i3.endsWith(".wasm") ? this.asset(`${t}/nosmai_engine.wasm`) : i3 });
      if (this.module = a4, await this.applyLicence(), a4.ccall("nosmai_set_canvas", null, ["string"], [e2]), !a4.ccall("nosmai_init", "number", [], [])) throw new Error(a4.ccall("nosmai_last_error", "string", [], []));
      return this.gl = this.canvas.getContext("webgl2"), await this.loadModels(), this;
    }
    async applyLicence() {
      const e2 = await b();
      this.module.ccall("nosmai_license_verify", null, ["string", "string", "string", "string", "string"], [this.options.apiKey, e2, w, p2(), ""]);
    }
    async loadModels() {
      const e2 = this.module;
      for (const [t, r3] of Object.entries(y)) {
        const n = await fetch(this.asset(`models/${r3}`));
        if (!n.ok) throw new Error(`model ${r3}: HTTP ${n.status}`);
        const a4 = new Uint8Array(await n.arrayBuffer()), i3 = e2._malloc(a4.length);
        if (!i3) throw new Error(`could not allocate for ${r3}`);
        let o3 = 0;
        try {
          e2.HEAPU8.set(a4, i3), o3 = e2.ccall("nosmai_inference_load_model", "number", ["number", "number", "number", "number"], [Number(t), i3, a4.length, 1]);
        } finally {
          e2._free(i3);
        }
        if (!o3) throw new Error(`engine rejected ${r3}: ${this.lastError()}`);
      }
      return e2.ccall("nosmai_inference_ready", "number", [], []) !== 0;
    }
    lastError() {
      return this.module.ccall("nosmai_last_error", "string", [], []);
    }
    refreshCameraTexture() {
      const e2 = this.module.ccall("nosmai_camera_texture", "number", [], []);
      this.cameraTexture = e2 ? this.module.GL.textures[e2] : null;
    }
    buildChain(e2, t) {
      if (!(e2 > 0) || !(t > 0)) throw new r({ type: "stateError", code: "NO_PREVIEW", message: "the preview is not running, so there is no size to build for \u2014 call camera.start() (or await the preview) before applying an effect" });
      if (this.width = e2, this.height = t, !this.module.ccall("nosmai_build_chain", "number", ["number", "number"], [e2, t])) throw new r({ type: "effectLoadFailed", code: "BUILD_CHAIN_FAILED", message: `could not build the render chain: ${this.lastError()}` });
      this.refreshCameraTexture(), this.renderFailed = false, this.module.ccall("nosmai_set_mirror", null, ["number"], [this.mirrored ? 1 : 0]);
    }
    applyEffect(e2) {
      return this.queue(async () => {
        const t = this.module, r3 = t._malloc(e2.length);
        if (!r3) throw new Error("could not allocate for the package");
        let n = 0;
        try {
          t.HEAPU8.set(e2, r3), n = t.ccall("nosmai_load_package", "number", ["number", "number"], [r3, e2.length]);
        } finally {
          t._free(r3);
        }
        if (!n) throw new r({ type: "effectLoadFailed", code: "PACKAGE_LOAD_FAILED", message: `the engine rejected the package: ${this.lastError()}` });
        if (!(!(this.width > 0) || !(this.height > 0))) {
          this.swapping = true;
          try {
            this.buildChain(this.width, this.height);
          } finally {
            this.swapping = false;
          }
        }
      });
    }
    clearEffect() {
      return this.queue(async () => {
        this.swapping = true;
        try {
          if (this.module.ccall("nosmai_clear_package", null, [], []), !(this.width > 0) || !(this.height > 0)) return;
          this.buildChain(this.width, this.height);
        } finally {
          this.swapping = false;
        }
      });
    }
    queue(e2) {
      const t = this.swapQueue.then(e2, e2);
      return this.swapQueue = t.catch(() => {
      }), t;
    }
    setSkinSmoothing(e2) {
      this.module?.ccall("nosmai_beauty_set_skin_smoothing", null, ["number"], [e2]);
    }
    setSkinWhitening(e2) {
      this.module?.ccall("nosmai_beauty_set_skin_whitening", null, ["number"], [e2]);
    }
    setSharpening(e2) {
      this.module?.ccall("nosmai_beauty_set_sharpening", null, ["number"], [e2]);
    }
    setContourHighlighter(e2) {
      this.module?.ccall("nosmai_beauty_set_contour_highlighter", null, ["number"], [e2]);
    }
    setDarkCircleCorrector(e2) {
      this.module?.ccall("nosmai_beauty_set_dark_circle_corrector", null, ["number"], [e2]);
    }
    effectParameters() {
      const e2 = this.module?.ccall("nosmai_effect_parameters", "string", [], []);
      if (!e2) return [];
      try {
        return JSON.parse(e2);
      } catch {
        return [];
      }
    }
    setTeethWhitening(e2) {
      this.module?.ccall("nosmai_beauty_set_teeth_whitening", null, ["number"], [e2]);
    }
    setEyeColor(e2, t, r3, n) {
      this.module?.ccall("nosmai_beauty_set_eye_color", null, ["number", "number", "number", "number"], [e2, t, r3, n]);
    }
    setReshape(e2, t) {
      this.module?.ccall("nosmai_beauty_set_reshape", null, ["number", "number"], [e2, t]);
    }
    applyMakeup(e2, t, r3, n, a4, i3) {
      this.module?.ccall("nosmai_makeup_apply", null, ["number", "number", "number", "number", "number", "number"], [e2, t, r3, n, a4, i3]);
    }
    setMakeupIntensity(e2, t) {
      this.module?.ccall("nosmai_makeup_set_intensity", null, ["number", "number"], [e2, t]);
    }
    makeupLayerActive(e2) {
      return this.module?.ccall("nosmai_makeup_has_layer", "number", ["number"], [e2]) === 1;
    }
    setEyeColorIntensity(e2) {
      this.module?.ccall("nosmai_beauty_set_eye_color_intensity", null, ["number"], [e2]);
    }
    eyeColorActive() {
      return this.module?.ccall("nosmai_beauty_eye_color_active", "number", [], []) === 1;
    }
    clearReshapes() {
      this.module?.ccall("nosmai_beauty_clear_reshapes", null, [], []);
    }
    hasActiveReshape() {
      return this.module?.ccall("nosmai_beauty_has_active_reshape", "number", [], []) === 1;
    }
    pausePackage() {
      this.module?.ccall("nosmai_package_pause", null, [], []);
    }
    resumePackage() {
      this.module?.ccall("nosmai_package_resume", null, [], []);
    }
    clearMakeup() {
      this.module?.ccall("nosmai_makeup_clear", null, [], []);
    }
    clearBeauty() {
      this.module?.ccall("nosmai_beauty_clear", null, [], []);
    }
    beautyActiveMask() {
      return this.module?.ccall("nosmai_beauty_active_mask", "number", [], []) ?? 0;
    }
    setHairColor(e2, t, r3, n) {
      this.module?.ccall("nosmai_hair_set_color", null, ["number", "number", "number", "number"], [e2, t, r3, n]);
    }
    setHairIntensity(e2) {
      this.module?.ccall("nosmai_hair_set_intensity", null, ["number"], [e2]);
    }
    hairEnabled() {
      return this.module?.ccall("nosmai_hair_is_enabled", "number", [], []) === 1;
    }
    clearHairColor() {
      this.module?.ccall("nosmai_hair_clear", null, [], []);
    }
    get recordingSupported() {
      return p();
    }
    get isRecording() {
      return this.recorder?.isRecording ?? false;
    }
    get recordingDuration() {
      return this.recorder?.duration ?? 0;
    }
    startRecording(e2, t, r3) {
      if (!this.canvas) throw new r({ type: "stateError", message: "there is no canvas to record \u2014 call attach() first" });
      this.recorder || (this.recorder = new f(this.mirror, r3)), this.recorder.start(this.canvas, e2, t);
    }
    stopRecording() {
      return this.recorder ? this.recorder.stop() : Promise.resolve({ success: false, duration: 0, fileSize: 0, error: "not currently recording" });
    }
    cancelRecording() {
      this.recorder?.cancel();
    }
    acquireOutput() {
      if (!this.canvas) throw new r({ type: "stateError", message: "there is no canvas to publish \u2014 call attach() first" });
      return this.mirror.acquire(this.canvas);
    }
    releaseOutput() {
      this.mirror.release();
    }
    get outputActive() {
      return this.mirror.active;
    }
    async capturePhoto(e2, t, r3) {
      if (!this.canvas) throw new r({ type: "stateError", message: "there is no canvas to capture \u2014 call attach() first" });
      return e2 && this.running && !this.swapping && e2.readyState >= e2.HAVE_CURRENT_DATA && this.renderFrame(e2), l(this.canvas, t, r3);
    }
    setBackgroundBlur(e2) {
      this.module?.ccall("nosmai_background_set_blur", null, ["number"], [e2]);
    }
    setBackgroundColor(e2, t, r3, n) {
      this.module?.ccall("nosmai_background_set_color", null, ["number", "number", "number", "number"], [e2, t, r3, n]);
    }
    setBackgroundImage(e2, t, r3) {
      const n = this.module;
      if (!n) return;
      const a4 = n._malloc(e2.length);
      if (!a4) throw new Error("could not allocate for the background image");
      try {
        n.HEAPU8.set(e2, a4), n.ccall("nosmai_background_set_image", null, ["number", "number", "number"], [a4, t, r3]);
      } finally {
        n._free(a4);
      }
    }
    setBrightness(e2) {
      this.module?.ccall("nosmai_color_set_brightness", null, ["number"], [e2]);
    }
    setContrast(e2) {
      this.module?.ccall("nosmai_color_set_contrast", null, ["number"], [e2]);
    }
    setHsb(e2, t, r3) {
      this.module?.ccall("nosmai_color_set_hsb", null, ["number", "number", "number"], [e2, t, r3]);
    }
    setWhiteBalance(e2, t) {
      this.module?.ccall("nosmai_color_set_white_balance", null, ["number", "number"], [e2, t]);
    }
    setRgb(e2, t, r3) {
      this.module?.ccall("nosmai_color_set_rgb", null, ["number", "number", "number"], [e2, t, r3]);
    }
    setGrayscale(e2) {
      this.module?.ccall("nosmai_color_set_grayscale", null, ["number"], [e2 ? 1 : 0]);
    }
    setLut(e2, t) {
      const r3 = this.module;
      if (!r3) return false;
      const n = r3._malloc(e2.length);
      if (!n) throw new Error("could not allocate for the LUT");
      try {
        return r3.HEAPU8.set(e2, n), r3.ccall("nosmai_color_set_lut", "number", ["number", "number", "number"], [n, e2.length, t]) === 1;
      } finally {
        r3._free(n);
      }
    }
    setLutIntensity(e2) {
      this.module?.ccall("nosmai_color_set_lut_intensity", null, ["number"], [e2]);
    }
    clearColor() {
      this.module?.ccall("nosmai_color_clear", null, [], []);
    }
    clearBackground() {
      this.module?.ccall("nosmai_background_clear", null, [], []);
    }
    setMirror(e2) {
      this.mirrored = e2, this.module?.ccall("nosmai_set_mirror", null, ["number"], [e2 ? 1 : 0]);
    }
    start(e2, t) {
      this.running = true;
      const r3 = () => {
        if (!this.swapping && e2.readyState >= e2.HAVE_CURRENT_DATA) {
          try {
            this.renderFrame(e2);
          } catch {
            this.renderFailed || (this.renderFailed = true);
          }
          if (this.recorder?.frame(), this.mirror.frame(), t) try {
            t();
          } catch {
          }
        }
      }, n = e2;
      let a4 = false;
      const i3 = () => {
        if (a4 || !this.running) return;
        a4 = true;
        const o3 = () => {
          if (a4 = false, !!this.running) try {
            r3();
          } finally {
            i3();
          }
        };
        typeof n.requestVideoFrameCallback == "function" ? n.requestVideoFrameCallback(o3) : requestAnimationFrame(o3);
      };
      return i3(), () => {
        this.running = false;
      };
    }
    renderFrame(e2) {
      const t = this.gl;
      t.bindTexture(t.TEXTURE_2D, this.cameraTexture), t.pixelStorei(t.UNPACK_FLIP_Y_WEBGL, false), t.texImage2D(t.TEXTURE_2D, 0, t.RGBA, t.RGBA, t.UNSIGNED_BYTE, e2), this.module.ccall("nosmai_face_active_models", "number", [], []) !== 0 && this.module.ccall("nosmai_face_process", "number", ["number", "number", "number"], [this.width, this.height, e2.currentTime]);
      const r3 = performance.now();
      this.module.ccall("nosmai_render_texture_frame", "number", ["number", "number", "number"], [this.width, this.height, e2.currentTime]), this.renderMsTotal += performance.now() - r3, this.renderMsCount += 1, this.frames += 1;
    }
    sampleFps() {
      const e2 = performance.now();
      if (!this.lastSample) return this.frames === 0 || (this.lastSample = e2, this.frames = 0), null;
      const t = e2 - this.lastSample;
      if (t < 500) return null;
      const r3 = Math.round(this.frames * 1e3 / t);
      return this.frames = 0, this.lastSample = e2, r3;
    }
    takeRenderMs() {
      if (this.renderMsCount === 0) return null;
      const e2 = this.renderMsTotal / this.renderMsCount;
      return this.renderMsTotal = 0, this.renderMsCount = 0, e2;
    }
    adaptRenderScale(e2) {
      this.scaler.tick(e2, this.takeRenderMs());
    }
    pinRenderScale(e2) {
      this.scaler.pin(e2);
    }
    get renderScale() {
      return this.scaler.scale;
    }
    faceTracked() {
      return this.module ? this.module.ccall("nosmai_face_tracked", "number", [], []) !== 0 : false;
    }
    dispose() {
      this.running = false;
    }
  };
  function E() {
    const s4 = import_meta.url, e2 = s4.slice(0, s4.lastIndexOf("/"));
    return e2.endsWith("/dist") ? e2.slice(0, -5) : e2;
  }
  var k = 0;
  function v(s4) {
    return s4.id || (s4.id = `nosmai-canvas-${++k}`), `#${s4.id}`;
  }

  // node_modules/@nosmai/web-sdk/dist/events.js
  var h3 = class {
    constructor() {
      __publicField(this, "handlers", /* @__PURE__ */ new Map());
    }
    on(t, s4) {
      let e2 = this.handlers.get(t);
      return e2 || (e2 = /* @__PURE__ */ new Set(), this.handlers.set(t, e2)), e2.add(s4), () => {
        e2.delete(s4);
      };
    }
    off(t, s4) {
      this.handlers.get(t)?.delete(s4);
    }
    emit(t, s4) {
      const e2 = this.handlers.get(t);
      if (e2) for (const n of [...e2]) try {
        n(s4);
      } catch {
      }
    }
  };

  // node_modules/@nosmai/web-sdk/dist/nosmai.js
  init_errors();

  // node_modules/@nosmai/web-sdk/dist/beauty-types.js
  var e = { lipSize: 0, faceSlim: 1, eyeSize: 2, noseSize: 3, chin: 4, brow: 5, browThickness: 6, jaw: 7, mouthWidth: 8, forehead: 9 };
  var s2 = { [e.lipSize]: 1, [e.faceSlim]: 1.2, [e.eyeSize]: 1.3, [e.noseSize]: 0.5, [e.chin]: 0.8, [e.brow]: 1, [e.browThickness]: 1, [e.jaw]: 1, [e.mouthWidth]: 1, [e.forehead]: 1 };
  var a2 = { [e.lipSize]: "Lip size", [e.faceSlim]: "Face slim", [e.eyeSize]: "Eye size", [e.noseSize]: "Nose size", [e.chin]: "Chin", [e.brow]: "Brow height", [e.browThickness]: "Brow thickness", [e.jaw]: "Jaw width", [e.mouthWidth]: "Mouth width", [e.forehead]: "Forehead" };
  function u3(r3) {
    const t = r3.replace("#", ""), o3 = parseInt(t.length === 3 ? t.split("").map((i3) => i3 + i3).join("") : t, 16);
    return { r: (o3 >> 16 & 255) / 255, g: (o3 >> 8 & 255) / 255, b: (o3 & 255) / 255 };
  }

  // node_modules/@nosmai/web-sdk/dist/nosmai.js
  var d2 = (s4) => Number.isFinite(s4) ? Math.max(-1, Math.min(1, s4)) : 0;
  var w2 = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    async start(e2 = {}) {
      return this.sdk._startCamera(e2);
    }
    stop() {
      this.sdk._stopCamera();
    }
    async switchCamera() {
      return this.sdk._switchCamera();
    }
    setMirror(e2) {
      this.sdk._setMirror(e2);
    }
    get isRunning() {
      return this.sdk._running;
    }
    get position() {
      return this.sdk._position;
    }
    async list() {
      return (await navigator.mediaDevices.enumerateDevices()).filter((i3) => i3.kind === "videoinput");
    }
  };
  var v3 = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    async apply(e2) {
      return this.sdk._applyEffect(e2);
    }
    async clear() {
      return this.sdk._clearEffect();
    }
    get active() {
      return this.sdk._activeEffect;
    }
    get isActive() {
      return this.sdk._engine?.effectIsActive() ?? false;
    }
    get activeType() {
      return this.sdk._engine?.activeEffectType() ?? "";
    }
    parameters() {
      return this.sdk._engine?.effectParameters() ?? [];
    }
    setParameter(e2, i3) {
      return this.sdk._engine?.setEffectParameter(e2, i3) ?? false;
    }
    setParameterString(e2, i3) {
      return this.sdk._engine?.setEffectParameterString(e2, i3) ?? false;
    }
    pause() {
      this.sdk._engine?.pausePackage();
    }
    resume() {
      this.sdk._engine?.resumePackage();
    }
    getParameter(e2) {
      return this.sdk._engine?.getEffectParameter(e2) ?? NaN;
    }
    getParameterString(e2) {
      return this.sdk._engine?.getEffectParameterString(e2) ?? "";
    }
  };
  var b2 = class {
    constructor(e2) {
      __publicField(this, "sdk");
      __publicField(this, "makeup");
      __publicField(this, "reshape");
      this.sdk = e2, this.makeup = new C2(e2), this.reshape = new E3(e2);
    }
    setSkinSmoothing(e2) {
      this.sdk._engine?.setSkinSmoothing(e2);
    }
    setSkinWhitening(e2) {
      this.sdk._engine?.setSkinWhitening(e2);
    }
    setTeethWhitening(e2) {
      this.sdk._engine?.setTeethWhitening(e2);
    }
    setSharpening(e2) {
      this.sdk._engine?.setSharpening(e2);
    }
    setContourHighlighter(e2) {
      this.sdk._engine?.setContourHighlighter(e2);
    }
    setDarkCircleCorrector(e2) {
      this.sdk._engine?.setDarkCircleCorrector(e2);
    }
    setEyeColor(e2, i3 = 0.5) {
      const t = typeof e2 == "string" ? u3(e2) : e2;
      this.sdk._engine?.setEyeColor(t.r, t.g, t.b, i3);
    }
    setEyeColorIntensity(e2) {
      this.sdk._engine?.setEyeColorIntensity(e2);
    }
    get isEyeColorActive() {
      return this.sdk._engine?.eyeColorActive() ?? false;
    }
    clear() {
      this.sdk._engine?.clearBeauty();
    }
    get activeMask() {
      return this.sdk._engine?.beautyActiveMask() ?? 0;
    }
  };
  var E3 = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    set(e2, i3) {
      this.sdk._engine?.setReshape(e2, d2(i3) * s2[e2]);
    }
    setAbsolute(e2, i3) {
      this.sdk._engine?.setReshape(e2, i3);
    }
    clear() {
      this.sdk._engine?.clearReshapes();
    }
    get isActive() {
      return this.sdk._engine?.hasActiveReshape() ?? false;
    }
  };
  var C2 = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    apply(e2, i3, t, n = 0.5) {
      const r3 = typeof t == "string" ? u3(t) : t;
      this.sdk._engine?.applyMakeup(e2, i3, r3.r, r3.g, r3.b, n);
    }
    setIntensity(e2, i3) {
      this.sdk._engine?.setMakeupIntensity(e2, i3);
    }
    isActive(e2) {
      return this.sdk._engine?.makeupLayerActive(e2) ?? false;
    }
    remove(e2) {
      this.sdk._engine?.applyMakeup(e2, 0, 0, 0, 0, 0);
    }
    clear() {
      this.sdk._engine?.clearMakeup();
    }
  };
  var S = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    setColor(e2, i3 = 0.5) {
      const t = typeof e2 == "string" ? u3(e2) : e2;
      this.sdk._engine?.setHairColor(t.r, t.g, t.b, i3);
    }
    setIntensity(e2) {
      this.sdk._engine?.setHairIntensity(e2);
    }
    get isEnabled() {
      return this.sdk._engine?.hairEnabled() ?? false;
    }
    clear() {
      this.sdk._engine?.clearHairColor();
    }
  };
  var R = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    stream() {
      const e2 = this.sdk._engine;
      if (!e2) throw new r({ type: "sdkNotInitialized", message: "initialize() and attach() before publishing the output" });
      return e2.acquireOutput();
    }
    videoTrack() {
      const [e2] = this.stream().getVideoTracks();
      if (!e2) throw new r({ type: "platformError", code: "NO_OUTPUT_TRACK", message: "the output stream produced no video track" });
      return e2;
    }
    get isActive() {
      return this.sdk._engine?.outputActive ?? false;
    }
    release() {
      this.sdk._engine?.releaseOutput();
    }
  };
  var A = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    get isSupported() {
      return this.sdk._engine?.recordingSupported ?? p();
    }
    get isRecording() {
      return this.sdk._engine?.isRecording ?? false;
    }
    get duration() {
      return this.sdk._engine?.recordingDuration ?? 0;
    }
    start(e2 = {}) {
      const i3 = this.sdk._engine;
      if (!i3) throw new r({ type: "sdkNotInitialized", message: "initialize() and attach() before recording" });
      i3.startRecording(this.sdk._stream, e2, (t) => {
        this.sdk._emitRecordingProgress(t);
      });
    }
    stop() {
      return this.sdk._engine?.stopRecording() ?? Promise.resolve({ success: false, duration: 0, fileSize: 0, error: "the SDK is not initialised" });
    }
    cancel() {
      this.sdk._engine?.cancelRecording();
    }
    capturePhoto(e2 = "image/png", i3) {
      const t = this.sdk._engine;
      return t ? t.capturePhoto(this.sdk._video, e2, i3) : Promise.reject(new r({ type: "sdkNotInitialized", message: "initialize() and attach() before capturing" }));
    }
  };
  var I = class {
    constructor(e2) {
      __publicField(this, "sdk");
      __publicField(this, "_videoStop", null);
      this.sdk = e2;
    }
    blur(e2) {
      this._stopVideo(), this.sdk._engine?.setBackgroundBlur(e2);
    }
    color(e2, i3 = 1) {
      this._stopVideo();
      const t = typeof e2 == "string" ? u3(e2) : e2;
      this.sdk._engine?.setBackgroundColor(t.r, t.g, t.b, i3);
    }
    async image(e2) {
      this._stopVideo();
      const { rgba: i3, width: t, height: n } = await B(e2);
      this.sdk._engine?.setBackgroundImage(i3, t, n);
    }
    async video(e2) {
      this._stopVideo();
      const i3 = e2 instanceof HTMLVideoElement ? e2 : Object.assign(document.createElement("video"), { src: typeof e2 == "string" ? e2 : URL.createObjectURL(e2), crossOrigin: "anonymous" });
      i3.muted = true, i3.loop = true, i3.playsInline = true, await i3.play();
      const t = document.createElement("canvas"), n = t.getContext("2d", { willReadFrequently: true });
      if (!n) throw new r({ type: "platformError", code: "BACKGROUND_VIDEO_UNSUPPORTED", message: "could not create the 2D context the background video needs" });
      let r3 = true, c4 = null;
      const h6 = () => {
        if (!r3) return;
        const o3 = i3.videoWidth, g2 = i3.videoHeight;
        if (o3 > 0 && g2 > 0) {
          (t.width !== o3 || t.height !== g2) && (t.width = o3, t.height = g2), n.drawImage(i3, 0, 0);
          const f4 = n.getImageData(0, 0, o3, g2);
          this.sdk._engine?.setBackgroundImage(new Uint8Array(f4.data.buffer), o3, g2);
        }
        typeof i3.requestVideoFrameCallback == "function" ? i3.requestVideoFrameCallback(h6) : c4 = setTimeout(h6, 1e3 / 30);
      };
      this._videoStop = () => {
        r3 = false, c4 !== null && (clearTimeout(c4), c4 = null), i3.pause(), i3 !== e2 && i3.src.startsWith("blob:") && URL.revokeObjectURL(i3.src);
      }, h6();
    }
    _stopVideo() {
      this._videoStop?.(), this._videoStop = null;
    }
    clear() {
      this._stopVideo(), this.sdk._engine?.clearBackground();
    }
  };
  async function B(s4) {
    let e2, i3, t;
    if (typeof s4 == "string" || s4 instanceof Blob) {
      const c4 = await createImageBitmap(typeof s4 == "string" ? await (await fetch(s4)).blob() : s4);
      e2 = c4, i3 = c4.width, t = c4.height;
    } else s4 instanceof ImageBitmap || s4 instanceof HTMLCanvasElement ? (e2 = s4, i3 = s4.width, t = s4.height) : (e2 = s4, i3 = s4.naturalWidth || s4.width, t = s4.naturalHeight || s4.height);
    const n = document.createElement("canvas");
    n.width = i3, n.height = t;
    const r3 = n.getContext("2d", { willReadFrequently: true });
    if (!r3) throw new r({ type: "platformError", message: "no 2D context for the background image" });
    return r3.drawImage(e2, 0, 0), { rgba: new Uint8Array(r3.getImageData(0, 0, i3, t).data.buffer), width: i3, height: t };
  }
  var T3 = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    setBrightness(e2) {
      this.sdk._engine?.setBrightness(d2(e2));
    }
    setContrast(e2) {
      this.sdk._engine?.setContrast(1 + d2(e2));
    }
    setContrastMultiplier(e2) {
      this.sdk._engine?.setContrast(e2);
    }
    setSaturation(e2) {
      this.sdk._engine?.setHsb(0, 1 + d2(e2), 1);
    }
    setHsb(e2, i3 = 1, t = 1) {
      this.sdk._engine?.setHsb(e2, i3, t);
    }
    setWarmth(e2, i3 = 0) {
      this.sdk._engine?.setWhiteBalance(5e3 + d2(e2) * 5e3, i3);
    }
    setWhiteBalance(e2, i3 = 0) {
      this.sdk._engine?.setWhiteBalance(e2, i3);
    }
    setRgb(e2 = 0, i3 = 0, t = 0) {
      this.sdk._engine?.setRgb(1 + d2(e2), 1 + d2(i3), 1 + d2(t));
    }
    setRgbGains(e2 = 1, i3 = 1, t = 1) {
      this.sdk._engine?.setRgb(e2, i3, t);
    }
    setGrayscale(e2) {
      this.sdk._engine?.setGrayscale(e2);
    }
    async setLut(e2, i3 = 1) {
      let t;
      if (typeof e2 == "string") {
        const n = await fetch(e2);
        if (!n.ok) throw new r({ type: "effectNotFound", message: `${e2}: HTTP ${n.status}` });
        t = new Uint8Array(await n.arrayBuffer());
      } else e2 instanceof Blob ? t = new Uint8Array(await e2.arrayBuffer()) : e2 instanceof Uint8Array ? t = e2 : t = new Uint8Array(e2);
      if (this.sdk._engine?.setLut(t, i3) === false) throw new r({ type: "effectInvalidFormat", message: "the LUT PNG could not be decoded" });
    }
    setLutIntensity(e2) {
      this.sdk._engine?.setLutIntensity(e2);
    }
    clear() {
      this.sdk._engine?.clearColor();
    }
  };
  var P = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    get isReady() {
      return this.sdk._engine?.gameReady() ?? false;
    }
    tap(e2, i3) {
      return this.sdk._engine?.gameTap(e2, i3) ?? false;
    }
    input(e2, i3 = 0, t = 0, n = 0) {
      return this.sdk._engine?.gameInput(e2, i3, t, n) ?? false;
    }
    pause() {
      this.sdk._engine?.gamePause();
    }
    resume() {
      this.sdk._engine?.gameResume();
    }
    restart() {
      this.sdk._engine?.gameRestart();
    }
  };
  var F = class {
    constructor(e2) {
      __publicField(this, "sdk");
      this.sdk = e2;
    }
    requireCloudFeature() {
      if (!this.sdk.isFeatureEnabled("cloudFilters")) throw new r({ type: "invalidLicense", code: "FEATURE_NOT_LICENSED", message: "cloud filters are not enabled for this licence key" });
    }
    async list(e2 = {}) {
      this.requireCloudFeature();
      const { listCloudEffects: i3 } = await Promise.resolve().then(() => (init_cloud(), cloud_exports));
      return i3({ apiKey: this.sdk._apiKey, ...e2 });
    }
    async download(e2, i3) {
      this.requireCloudFeature();
      const { downloadCloudEffect: t } = await Promise.resolve().then(() => (init_cloud(), cloud_exports));
      return t(e2, { apiKey: this.sdk._apiKey, onProgress: i3 });
    }
    async isDownloaded(e2) {
      const { isEffectCached: i3 } = await Promise.resolve().then(() => (init_effect_cache(), effect_cache_exports));
      return i3(e2);
    }
    async downloadedIds() {
      const { cachedEffectIds: e2 } = await Promise.resolve().then(() => (init_effect_cache(), effect_cache_exports));
      return e2();
    }
    async clearCache(e2) {
      const { clearEffectCache: i3 } = await Promise.resolve().then(() => (init_effect_cache(), effect_cache_exports));
      return i3(e2);
    }
    async apply(e2, i3) {
      const t = await this.download(e2, i3);
      await this.sdk.effects.apply(t);
    }
  };
  var _l = class _l {
    constructor() {
      __publicField(this, "_engine", null);
      __publicField(this, "bus", new h3());
      __publicField(this, "stream", null);
      __publicField(this, "ownsStream", true);
      __publicField(this, "video", null);
      __publicField(this, "stopRender", null);
      __publicField(this, "assetBase");
      __publicField(this, "_apiKey", "");
      __publicField(this, "licence", { status: "unverified", secondsRemaining: 0, watermarked: true, blurred: false });
      __publicField(this, "_running", false);
      __publicField(this, "_position", "front");
      __publicField(this, "_activeEffect", null);
      __publicField(this, "mirror", "auto");
      __publicField(this, "canvas", null);
      __publicField(this, "initialized", false);
      __publicField(this, "camera", new w2(this));
      __publicField(this, "effects", new v3(this));
      __publicField(this, "beauty", new b2(this));
      __publicField(this, "hair", new S(this));
      __publicField(this, "background", new I(this));
      __publicField(this, "color", new T3(this));
      __publicField(this, "cloud", new F(this));
      __publicField(this, "game", new P(this));
      __publicField(this, "recording", new A(this));
      __publicField(this, "output", new R(this));
      __publicField(this, "sampling", null);
      __publicField(this, "sampleTick", 0);
    }
    get _stream() {
      return this.stream;
    }
    get _video() {
      return this.video;
    }
    _emitRecordingProgress(e2) {
      this.bus.emit("recordingProgress", e2);
    }
    static get instance() {
      return _l._instance || (_l._instance = new _l()), _l._instance;
    }
    static async initialize(e2, i3 = {}) {
      const t = _l.instance;
      if (t.initialized) return { ok: t.licence.status !== "invalid", licence: t.licenceState };
      if (t._apiKey = e2, t.assetBase = i3.assetBase, !e2) {
        const n = new r({ type: "invalidLicense", message: "no licence key was supplied" });
        return t.bus.emit("error", n), { ok: false, licence: t.licence, error: n };
      }
      return t.initialized = true, { ok: true, licence: t.licence };
    }
    get isInitialized() {
      return this.initialized;
    }
    get isProcessing() {
      return this._running;
    }
    isFeatureEnabled(e2) {
      return this._engine?.licenceFeature(e2) ?? false;
    }
    get isCloudFilterEnabled() {
      return this.isFeatureEnabled("cloudFilters");
    }
    get isBeautyEffectEnabled() {
      return this.isFeatureEnabled("beautyEffects");
    }
    get licenceState() {
      return this._engine ? this._engine.licenceState() : this.licence;
    }
    on(e2, i3) {
      return this.bus.on(e2, i3);
    }
    async attach(e2) {
      if (!e2.isConnected) throw new r({ type: "stateError", message: "the canvas must be in the document before attach()" });
      this.canvas = e2;
      const i3 = new T({ apiKey: this._apiKey, canvas: e2, assetBase: this.assetBase, onLicence: (t) => {
        this.licence = t, this.bus.emit("licenseStatusChanged", t);
      } });
      try {
        await i3.init();
      } catch (t) {
        const n = t instanceof r ? t : new r({ type: "webglUnavailable", message: t instanceof Error ? t.message : String(t), cause: t });
        throw this.bus.emit("error", n), n;
      }
      this._engine = i3;
    }
    async _startCamera(e2) {
      if (!this._engine) throw new r({ type: "sdkNotInitialized", message: "call attach(canvas) before starting the camera" });
      this._position = e2.position ?? this._position;
      let i3;
      if (e2.stream) i3 = e2.stream, this.ownsStream = false;
      else {
        try {
          i3 = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: e2.width ?? 1280 }, height: { ideal: e2.height ?? 720 }, facingMode: this._position === "front" ? "user" : "environment" }, audio: false });
        } catch (h6) {
          const o3 = s(h6);
          throw this.bus.emit("error", o3), o3;
        }
        this.ownsStream = true;
      }
      this.stream = i3;
      const t = this.video ?? document.createElement("video");
      t.playsInline = true, t.muted = true, t.isConnected || (t.style.cssText = "position:fixed;top:0;left:0;width:1px;height:1px;opacity:0.001;pointer-events:none;z-index:-1", document.body.appendChild(t)), t.srcObject = i3, await t.play(), this.video = t, t.videoWidth || await new Promise((h6) => {
        const o3 = () => {
          t.removeEventListener("loadedmetadata", o3), h6();
        };
        t.addEventListener("loadedmetadata", o3), setTimeout(o3, 2e3);
      });
      const n = i3.getVideoTracks()[0].getSettings(), r3 = n.width ?? t.videoWidth, c4 = n.height ?? t.videoHeight;
      this.canvas && (this.canvas.width !== r3 && (this.canvas.width = r3), this.canvas.height !== c4 && (this.canvas.height = c4)), this._engine.buildChain(r3, c4), this.applyMirror(), this.stopRender = this._engine.start(t, () => {
        const h6 = this._engine.drainGameEvents();
        for (const o3 of h6) this.bus.emit("gameEvent", o3);
      }), this._running = true, this.bus.emit("ready", void 0), this.startSampling();
    }
    _stopCamera() {
      this.stopRender?.(), this.stopRender = null, this.ownsStream && this.stream?.getTracks().forEach((e2) => e2.stop()), this.stream = null, this.ownsStream = true, this.video && (this.video.srcObject = null), this._running = false;
    }
    async _switchCamera() {
      if (!this._running) return false;
      this._position = this._position === "front" ? "back" : "front", this._stopCamera();
      try {
        return await this._startCamera({ position: this._position }), true;
      } catch {
        return this._position = this._position === "front" ? "back" : "front", false;
      }
    }
    _setMirror(e2) {
      this.mirror = e2, this.applyMirror();
    }
    applyMirror() {
      const e2 = this.mirror === "auto" ? this._position === "front" : this.mirror === "on";
      this._engine?.setMirror(e2);
    }
    async _applyEffect(e2) {
      if (!this._engine) throw new r({ type: "sdkNotInitialized", message: "attach(canvas) first" });
      let i3;
      if (typeof e2 == "string") {
        const t = await fetch(e2);
        if (!t.ok) throw new r({ type: "effectNotFound", message: `${e2}: HTTP ${t.status}` });
        i3 = new Uint8Array(await t.arrayBuffer());
      } else e2 instanceof Blob ? i3 = new Uint8Array(await e2.arrayBuffer()) : e2 instanceof Uint8Array ? i3 = e2 : i3 = new Uint8Array(e2);
      try {
        await this._engine.applyEffect(i3);
      } catch (t) {
        const n = new r({ type: "effectLoadFailed", message: t instanceof Error ? t.message : String(t), cause: t });
        throw this.bus.emit("error", n), n;
      }
    }
    async _clearEffect() {
      await this._engine?.clearEffect(), this._activeEffect = null;
    }
    startSampling() {
      if (this.sampling !== null) return;
      let e2 = null;
      this.sampling = window.setInterval(() => {
        if (!this._running || !this._engine) return;
        const i3 = this._engine.sampleFps();
        i3 !== null && this.bus.emit("fps", i3);
        const t = this._engine.faceTracked();
        t !== e2 && (e2 = t, this.bus.emit("faceDetected", t));
        const n = this._engine.licenceState();
        n.status !== this.licence.status && (this.licence = n, this.bus.emit("licenseStatusChanged", n)), (this.sampleTick = (this.sampleTick + 1) % 2) === 0 && i3 !== null && this._engine.adaptRenderScale(i3);
      }, 1e3);
    }
    dispose() {
      this.sampling !== null && (clearInterval(this.sampling), this.sampling = null), this._stopCamera(), this.video?.remove(), this.video = null, this._engine?.dispose(), this._engine = null, this.canvas = null, this.initialized = false;
    }
  };
  __publicField(_l, "_instance", null);
  var l3 = _l;

  // web_sdk_bridge/src/nosmai_bridge.js
  var root = typeof window !== "undefined" ? window : globalThis;
  root.NosmaiReactNativeWeb = Object.freeze({
    Nosmai: l3,
    version: "0.1.0-alpha.2"
  });
})();
