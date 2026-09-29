import http from '@ohos.net.http';
import preferences from '@ohos.data.preferences';
import util from '@ohos.util';
import { loadProxy, proxyOpt } from './HttpUtil';
import { tlog } from './TestLog';

/**
 * Unified LLM access layer (Android 2026-09-26 parity): the protocol field
 * routes to one of three wire formats while callers only deal with plain
 * text. openai = OpenAI-compatible chat/completions (+ SSE streaming,
 * NO_THINK learning, whole-text fallback), anthropic = Claude /messages,
 * google = Gemini :generateContent. Requests resolve through the active AiConfig.
 */

export interface AiConfig {
  /** vendor label: 智谱 / OpenAI / DeepSeek / 自定义 */
  provider: string;
  /** wire protocol: openai | anthropic | google (Android AiClient parity) */
  protocol: string;
  /** base URL, e.g. https://open.bigmodel.cn/api/paas/v4 */
  url: string;
  /** API key (Bearer / x-api-key / x-goog-api-key) */
  key: string;
  /** model name, e.g. glm-4.5-air */
  model: string;
  /** max output tokens */
  maxTokens: number;
  /** thinking mode (protocol-specific wire field) */
  thinking: boolean;
}

export const AI_PROVIDERS: string[] = ['\u667A\u8C31', 'OpenAI', 'DeepSeek', '\u81EA\u5B9A\u4E49'];
export const AI_PROVIDER_URLS: string[] = [
  'https://open.bigmodel.cn/api/paas/v4',
  'https://api.openai.com/v1',
  'https://api.deepseek.com/v1',
  ''
];

/** Android AiClient protocol constants + per-protocol default endpoints. */
export const PROTOCOL_OPENAI = 'openai';
export const PROTOCOL_ANTHROPIC = 'anthropic';
export const PROTOCOL_GOOGLE = 'google';
export const DEFAULT_URL_OPENAI = 'https://api.openai.com/v1';
export const DEFAULT_URL_ANTHROPIC = 'https://api.anthropic.com/v1';
export const DEFAULT_URL_GOOGLE = 'https://generativelanguage.googleapis.com/v1beta';

export function isProtocol(p: string): boolean {
  return p === PROTOCOL_ANTHROPIC || p === PROTOCOL_GOOGLE || p === PROTOCOL_OPENAI || p.length === 0;
}

export function defaultUrl(protocol: string): string {
  if (protocol === PROTOCOL_ANTHROPIC) {
    return DEFAULT_URL_ANTHROPIC;
  }
  if (protocol === PROTOCOL_GOOGLE) {
    return DEFAULT_URL_GOOGLE;
  }
  return DEFAULT_URL_OPENAI;
}

/** True when the wire is the OpenAI-compatible one (streaming + NO_THINK). */
function isOpenAi(protocol: string): boolean {
  return protocol.length === 0 || protocol === PROTOCOL_OPENAI;
}

export interface AiMessage {
  role: string;
  content: string;
}

const PREF_NAME = 'librera_ai';
const KEY_AI = 'ai';
/* Android 2026-09-25 parity: some models/endpoints reject the thinking
 * switch (e.g. glm-5.x answers 400). Remember such model+endpoint combos
 * and skip the parameter on later requests. */
const KEY_NO_THINK = 'no_thinking';
const NO_THINK: Set<string> = new Set<string>();
let noThinkLoaded = false;

/** Set by the last chat call: the model stopped at the output length cap
 * (Android TestResult.truncated parity). UIs append a note when true. */
export let lastTruncated = false;

function thinkKey(cfg: AiConfig): string {
  return cfg.model + '@' + cfg.url;
}

async function loadNoThinking(ctx?: Context): Promise<void> {
  if (noThinkLoaded) {
    return;
  }
  noThinkLoaded = true;
  if (ctx === undefined) {
    return;
  }
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_NO_THINK, '[]') as string;
    const arr: string[] = JSON.parse(json) as string[];
    for (let i = 0; i < arr.length; i++) {
      NO_THINK.add(arr[i]);
    }
  } catch (e) {
    /* start empty */
  }
}

async function learnNoThinking(cfg: AiConfig, ctx?: Context): Promise<void> {
  NO_THINK.add(thinkKey(cfg));
  if (ctx === undefined) {
    return;
  }
  try {
    const prefs = await getPrefs(ctx);
    const keys: string[] = [];
    NO_THINK.forEach((v: string): void => {
      keys.push(v);
    });
    await prefs.put(KEY_NO_THINK, JSON.stringify(keys));
    await prefs.flush();
    console.info('[AiClient] thinking param skipped for ' + thinkKey(cfg));
  } catch (e) {
    /* memory-only fallback */
  }
}

let prefInstance: preferences.Preferences | undefined = undefined;

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

export function defaultAiConfig(): AiConfig {
  const c: AiConfig = {
    provider: AI_PROVIDERS[0],
    protocol: PROTOCOL_OPENAI,
    url: AI_PROVIDER_URLS[0],
    key: '',
    model: 'glm-4.5-air',
    maxTokens: 8192,
    thinking: false
  };
  return c;
}

export async function loadAiConfig(ctx: Context): Promise<AiConfig> {
  const d = defaultAiConfig();
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_AI, '') as string;
    if (json.length === 0) {
      return d;
    }
    const r = JSON.parse(json) as AiConfig;
    const c: AiConfig = {
      provider: typeof r.provider === 'string' && r.provider.length > 0 ? r.provider : d.provider,
      protocol: typeof r.protocol === 'string' && r.protocol.length > 0 ? r.protocol : d.protocol,
      url: typeof r.url === 'string' ? r.url : d.url,
      key: typeof r.key === 'string' ? r.key : '',
      model: typeof r.model === 'string' ? r.model : d.model,
      maxTokens: typeof r.maxTokens === 'number' && r.maxTokens > 0 ? r.maxTokens : d.maxTokens,
      thinking: r.thinking === true
    };
    return c;
  } catch (e) {
    return d;
  }
}

export async function saveAiConfig(ctx: Context, c: AiConfig): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_AI, JSON.stringify(c));
    await prefs.flush();
    console.info('[AiClient] config saved: provider=' + c.provider + ' model=' + c.model);
  } catch (e) {
    console.error('[AiClient] config save failed');
  }
}

/** True when the config has the minimum fields for a request. */
export function aiConfigReady(c: AiConfig): boolean {
  return c.url.length > 0 && c.key.length > 0 && c.model.length > 0;
}

function joinUrl(base: string, path: string): string {
  return base.replace(/\/+$/, '') + path;
}

/** Android classify(): HTTP code -> short kind, wrapped into the message. */
function classify(code: number): string {
  if (code === 401 || code === 403) {
    return '\u8BA4\u8BC1\u5931\u8D25\uFF08\u5BC6\u94A5\u65E0\u6548\u6216\u672A\u6388\u6743\uFF09';
  }
  if (code === 429) {
    return '\u8BF7\u6C42\u8FC7\u4E8E\u9891\u7E41\uFF08\u9650\u6D41\uFF09';
  }
  if (code === 404) {
    return '\u6A21\u578B\u6216\u5730\u5740\u4E0D\u5B58\u5728';
  }
  return '\u670D\u52A1\u7AEF\u9519\u8BEF';
}

function aiHeaders(c: AiConfig): Record<string, string> {
  const h: Record<string, string> = { 'Content-Type': 'application/json' };
  if (c.protocol === PROTOCOL_ANTHROPIC) {
    h['x-api-key'] = c.key;
    h['anthropic-version'] = '2023-06-01';
  } else if (c.protocol === PROTOCOL_GOOGLE) {
    h['x-goog-api-key'] = c.key;
  } else {
    h['Authorization'] = 'Bearer ' + c.key;
  }
  return h;
}

/** Extract a readable error message from an OpenAI-style error body. */
function parseErrorBody(body: string): string {
  try {
    const o = JSON.parse(body) as Record<string, Object>;
    const err = o['error'] as Record<string, Object>;
    if (err !== undefined && err !== null && typeof err['message'] === 'string') {
      return err['message'] as string;
    }
    if (typeof o['msg'] === 'string') {
      return o['msg'] as string;
    }
    if (typeof o['message'] === 'string') {
      return o['message'] as string;
    }
  } catch (e) {
    /* fall through */
  }
  return body.length > 120 ? body.substring(0, 120) : body;
}

/** Anthropic /messages request body (extended thinking budget per Android). */
function anthropicBody(cfg: AiConfig, messages: AiMessage[], maxTokens: number): Record<string, Object> {
  let mt = maxTokens > 0 ? maxTokens : cfg.maxTokens;
  const bodyObj: Record<string, Object> = {
    'model': cfg.model,
    'max_tokens': mt,
    'messages': messages
  };
  if (cfg.thinking) {
    let budget = Math.min(2048, mt - 1024);
    if (budget < 1024) {
      budget = 1024;
      mt = 3072;
      bodyObj['max_tokens'] = mt;
    }
    const th: Record<string, Object> = { 'type': 'enabled', 'budget_tokens': budget };
    bodyObj['thinking'] = th;
  }
  return bodyObj;
}

/** Google :generateContent request body (thinkingBudget -1 dynamic / 0 off). */
function googleBody(cfg: AiConfig, messages: AiMessage[], maxTokens: number): Record<string, Object> {
  const parts: Record<string, string>[] = [];
  let text = '';
  for (let i = 0; i < messages.length; i++) {
    text += (i > 0 ? '\n\n' : '') + messages[i].content;
  }
  parts.push({ 'text': text });
  const p0: Record<string, Object> = { 'parts': parts };
  const contents: Record<string, Object>[] = [p0];
  const gen: Record<string, Object> = { 'maxOutputTokens': maxTokens > 0 ? maxTokens : cfg.maxTokens };
  const tc: Record<string, number> = { 'thinkingBudget': cfg.thinking ? -1 : 0 };
  gen['thinkingConfig'] = tc;
  const bodyObj: Record<string, Object> = { 'contents': contents, 'generationConfig': gen };
  return bodyObj;
}

function openAiBody(cfg: AiConfig, messages: AiMessage[], maxTokens: number, stream: boolean,
  withThinking: boolean): Record<string, Object> {
  const bodyObj: Record<string, Object> = {
    'model': cfg.model,
    'messages': messages,
    'max_tokens': maxTokens > 0 ? maxTokens : cfg.maxTokens,
    'stream': stream
  };
  if (withThinking) {
    const t: Record<string, string> = { 'type': cfg.thinking ? 'enabled' : 'disabled' };
    bodyObj['thinking'] = t;
  }
  return bodyObj;
}

/** Pull the reply text out of a protocol-specific non-stream response. */
function extractText(protocol: string, body: string): string {
  try {
    const o = JSON.parse(body) as Record<string, Object>;
    if (protocol === PROTOCOL_GOOGLE) {
      const cands = o['candidates'] as Array<Record<string, Object>>;
      const cont = cands[0]['content'] as Record<string, Object>;
      const parts = cont['parts'] as Array<Record<string, Object>>;
      let out = '';
      for (let i = 0; i < parts.length; i++) {
        if (typeof parts[i]['text'] === 'string') {
          out += parts[i]['text'] as string;
        }
      }
      return out;
    }
    if (protocol === PROTOCOL_ANTHROPIC) {
      /* extended thinking puts a {"type":"thinking"} block FIRST: concat
       * the actual text blocks instead of reading [0] (Android parity) */
      const content = o['content'] as Array<Record<string, Object>>;
      let out = '';
      for (let i = 0; i < content.length; i++) {
        if (content[i]['type'] === 'text' && typeof content[i]['text'] === 'string') {
          out += content[i]['text'] as string;
        }
      }
      return out;
    }
    const choices = o['choices'] as Array<Record<string, Object>>;
    const msg = choices[0]['message'] as Record<string, Object>;
    let content = msg !== undefined && msg !== null && typeof msg['content'] === 'string' ? msg['content'] as string : '';
    if (content.length === 0 && msg !== undefined && msg !== null && typeof msg['reasoning_content'] === 'string') {
      /* reasoning models: visible text may sit in reasoning_content */
      content = msg['reasoning_content'] as string;
    }
    return content;
  } catch (e) {
    /* a 2xx with an unexpected shape still proves connectivity */
    return '';
  }
}

/** True when the model stopped because it hit the output length cap. */
function isLengthTruncated(protocol: string, body: string): boolean {
  try {
    const o = JSON.parse(body) as Record<string, Object>;
    if (protocol === PROTOCOL_GOOGLE) {
      const cands = o['candidates'] as Array<Record<string, Object>>;
      return cands[0]['finishReason'] === 'MAX_TOKENS';
    }
    if (protocol === PROTOCOL_ANTHROPIC) {
      return o['stop_reason'] === 'max_tokens';
    }
    const choices = o['choices'] as Array<Record<string, Object>>;
    return choices[0]['finish_reason'] === 'length';
  } catch (e) {
    return false;
  }
}

/** Single non-stream request attempt (withThinking gates the GLM thinking switch). */
async function chatOnce(cfg: AiConfig, messages: AiMessage[], maxTokens: number, ctx: Context | undefined, withThinking: boolean): Promise<string> {
const t0: number = Date.now();
tlog('ai_req_start', 'model=' + cfg.model + ' proto=' + cfg.protocol);
  if (!aiConfigReady(cfg)) {
    return Promise.reject(new Error('\u8BF7\u5148\u5728\u504F\u597D\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B'));
  }
  const proto = cfg.protocol;
  let url: string;
  let bodyObj: Record<string, Object>;
  if (proto === PROTOCOL_ANTHROPIC) {
    url = joinUrl(cfg.url, '/messages');
    bodyObj = anthropicBody(cfg, messages, maxTokens);
  } else if (proto === PROTOCOL_GOOGLE) {
    url = joinUrl(cfg.url, '/models/' + cfg.model + ':generateContent');
    bodyObj = googleBody(cfg, messages, maxTokens);
  } else {
    url = joinUrl(cfg.url, '/chat/completions');
    bodyObj = openAiBody(cfg, messages, maxTokens, false, cfg.provider === AI_PROVIDERS[0] && withThinking);
  }
  const proxy = proxyOpt(await loadProxy(ctx));
  const client = http.createHttp();
  try {
    const resp = await client.request(url, {
      method: http.RequestMethod.POST,
      header: aiHeaders(cfg),
      extraData: JSON.stringify(bodyObj),
      connectTimeout: 15000,
      readTimeout: 60000,
      usingProxy: proxy
    });
    const text = resp.result as string;
    if (resp.responseCode < 200 || resp.responseCode >= 300) {
      tlog('ai_req_fail', 'http=' + resp.responseCode + ' ms=' + (Date.now() - t0));
      throw new Error(classify(resp.responseCode) + ' HTTP ' + resp.responseCode + ': ' + parseErrorBody(text));
    }
    const content = extractText(proto, text);
    if (content.length === 0) {
      /* 2xx but no usable text: reasoning models may spend the whole budget */
      throw new Error('\u6A21\u578B\u672A\u8FD4\u56DE\u5185\u5BB9');
    }
    lastTruncated = isLengthTruncated(proto, text);
    tlog('ai_req_done', 'ms=' + (Date.now() - t0));
    return content;
  } finally {
    client.destroy();
  }
}

/** HTTP 400 helper (thinking rejection / fallback decisions). */
function isHttp400(e: Object): boolean {
  const msg = e instanceof Error ? e.message : String(e);
  return msg.indexOf('HTTP 400') >= 0 && msg.indexOf(classify(400)) === 0;
}

function isCancelledError(e: Object): boolean {
  return (e instanceof Error ? e.message : String(e)) === 'CANCELLED';
}

/** Single chat completion (non-stream). Retries once without the thinking
 * switch when the endpoint rejects it with 400, then remembers the combo. */
export async function chatCompletion(cfg: AiConfig, messages: AiMessage[], maxTokens: number, ctx?: Context): Promise<string> {
  lastTruncated = false;
  await loadNoThinking(ctx);
  const withThinking = !NO_THINK.has(thinkKey(cfg));
  try {
    return await chatOnce(cfg, messages, maxTokens, ctx, withThinking);
  } catch (e) {
    if (withThinking && isOpenAi(cfg.protocol) && isHttp400(e as Object)) {
      await learnNoThinking(cfg, ctx);
      return await chatOnce(cfg, messages, maxTokens, ctx, false);
    }
    throw e as Error;
  }
}

/** Stream one request via SSE; onDelta delivers the accumulated text while
 * it grows. Rejects with a classified message on non-2xx responses. */
async function streamOnce(cfg: AiConfig, messages: AiMessage[], maxTokens: number,
  onDelta: (full: string) => void, ctx: Context | undefined, withThinking: boolean,
  isCancelled?: () => boolean): Promise<string> {
const t0: number = Date.now();
tlog('ai_req_start', 'stream model=' + cfg.model);
  if (!aiConfigReady(cfg)) {
    return Promise.reject(new Error('\u8BF7\u5148\u5728\u504F\u597D\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B'));
  }
  const bodyObj = openAiBody(cfg, messages, maxTokens, true, cfg.provider === AI_PROVIDERS[0] && withThinking);
  const proxy = proxyOpt(await loadProxy(ctx));
  const client = http.createHttp();
  const pend: number[] = [];
  let sseBuf = '';
  let full = '';
  let reasoning = '';
  let finish = '';
  let code = 0;
  let errBody = '';
  let rawBody = '';
  let settled = false;
  let watchdog = -1;
  return new Promise<string>((resolve: (v: string) => void, reject: (e: Error) => void) => {
    const finishOk = (v: string): void => {
      if (settled) {
        return;
      }
      settled = true;
      if (watchdog >= 0) {
        clearTimeout(watchdog);
      }
      tlog('ai_req_done', 'stream ms=' + (Date.now() - t0));
      client.destroy();
      resolve(v);
    };
    const finishErr = (msg: string): void => {
      if (settled) {
        return;
      }
      settled = true;
      lastTruncated = false;
      if (watchdog >= 0) {
        clearTimeout(watchdog);
      }
      tlog('ai_req_fail', 'stream ms=' + (Date.now() - t0));
      client.destroy();
      reject(new Error(msg));
    };
    /* utf-8 safe incremental decode: keep incomplete trailing sequences back */
    const decodeChunk = (chunk: ArrayBuffer): string => {
      const arr = new Uint8Array(chunk);
      for (let i = 0; i < arr.length; i++) {
        pend.push(arr[i]);
      }
      let keep = pend.length;
      for (let i = pend.length - 1; i >= 0 && i >= pend.length - 3; i--) {
        const b = pend[i];
        if (b < 0x80) {
          break;
        }
        if ((b & 0xC0) === 0x80) {
          continue;
        }
        const need = b >= 0xF0 ? 4 : (b >= 0xE0 ? 3 : 2);
        if (pend.length - i < need) {
          keep = i;
        }
        break;
      }
      const bytes = pend.splice(0, keep);
      return new util.TextDecoder('utf-8').decodeToString(new Uint8Array(bytes));
    };
    client.on('dataReceive', (chunk: ArrayBuffer) => {
      if (settled) {
        return;
      }
      if (isCancelled !== undefined && isCancelled()) {
        finishErr('CANCELLED');
        return;
      }
      const text = decodeChunk(chunk);
      rawBody += text;
      if (code >= 400) {
        errBody += text;
        return;
      }
      sseBuf += text;
      let idx = sseBuf.indexOf('\n');
      while (idx >= 0) {
        const line = sseBuf.substring(0, idx).replace(/\r/g, '');
        sseBuf = sseBuf.substring(idx + 1);
        if (line.startsWith('data:')) {
          const payload = line.substring(5).trim();
          if (payload.length > 0 && payload !== '[DONE]') {
            try {
              const o = JSON.parse(payload) as Record<string, Object>;
              const choices = o['choices'] as Array<Record<string, Object>>;
              if (choices !== undefined && choices !== null && choices.length > 0) {
                const delta = choices[0]['delta'] as Record<string, Object>;
                if (delta !== undefined && delta !== null) {
                  if (typeof delta['content'] === 'string' && (delta['content'] as string).length > 0) {
                    full += delta['content'] as string;
                    onDelta(full);
                  }
                  if (typeof delta['reasoning_content'] === 'string') {
                    reasoning += delta['reasoning_content'] as string;
                  }
                }
                const fr = choices[0]['finish_reason'];
                if (typeof fr === 'string' && (fr as string).length > 0) {
                  finish = fr as string;
                }
              }
            } catch (err) {
              /* ignore malformed line */
            }
          }
        }
        idx = sseBuf.indexOf('\n');
      }
    });
    client.on('dataEnd', () => {
      if (code >= 400) {
        finishErr(classify(code) + ' HTTP ' + code + ': ' + parseErrorBody(errBody));
        return;
      }
      /* endpoint ignored stream:true and answered a single JSON body */
      const trimmed = rawBody.trim();
      if (full.length === 0 && trimmed.startsWith('{')) {
        try {
          const o = JSON.parse(trimmed) as Record<string, Object>;
          const choices = o['choices'] as Array<Record<string, Object>>;
          if (choices !== undefined && choices !== null && choices.length > 0) {
            const msg = choices[0]['message'] as Record<string, Object>;
            if (msg !== undefined && msg !== null && typeof msg['content'] === 'string') {
              const content = msg['content'] as string;
              lastTruncated = isLengthTruncated(PROTOCOL_OPENAI, trimmed);
              onDelta(content);
              finishOk(content);
              return;
            }
          }
        } catch (err) {
          /* fall through with the streamed text */
        }
      }
      let reply = full;
      if (reply.length === 0 && reasoning.length > 0) {
        reply = reasoning;
      }
      lastTruncated = finish === 'length';
      finishOk(reply);
    });
    watchdog = setTimeout((): void => {
      finishErr('\u8BF7\u6C42\u8D85\u65F6');
    }, 180000) as number;
    client.requestInStream(joinUrl(cfg.url, '/chat/completions'), {
      method: http.RequestMethod.POST,
      header: aiHeaders(cfg),
      extraData: JSON.stringify(bodyObj),
      connectTimeout: 15000,
      readTimeout: 120000,
      usingProxy: proxy
    }).then((c: number) => {
      code = c;
    }).catch((err: Object) => {
      finishErr(String(err));
    });
  });
}

/** Streaming chat completion (OpenAI wire only; other protocols answer in
 * one block). Retries without the thinking switch on a thinking-caused 400,
 * and falls back to the non-stream path when the endpoint rejects streaming
 * or the transport fails (Android 2026-09-24/25). */
export async function chatCompletionStream(cfg: AiConfig, messages: AiMessage[], maxTokens: number,
  onDelta: (full: string) => void, ctx?: Context, isCancelled?: () => boolean): Promise<string> {
  lastTruncated = false;
  if (!isOpenAi(cfg.protocol)) {
    /* anthropic / google: single-block answers on their own wire */
    return chatCompletion(cfg, messages, maxTokens, ctx);
  }
  await loadNoThinking(ctx);
  const withThinking = !NO_THINK.has(thinkKey(cfg));
  try {
    return await streamOnce(cfg, messages, maxTokens, onDelta, ctx, withThinking, isCancelled);
  } catch (e) {
    if (isCancelledError(e as Object)) {
      throw e as Error;
    }
    if (withThinking && isHttp400(e as Object)) {
      await learnNoThinking(cfg, ctx);
      try {
        return await streamOnce(cfg, messages, maxTokens, onDelta, ctx, false, isCancelled);
      } catch (e2) {
        if (isCancelledError(e2 as Object)) {
          throw e2 as Error;
        }
        return await chatCompletion(cfg, messages, maxTokens, ctx);
      }
    }
    /* streaming unsupported or transport failure -> whole-text fallback */
    return await chatCompletion(cfg, messages, maxTokens, ctx);
  }
}

/** List model ids available for the key (GET /models, per-protocol auth). */
export async function listModels(cfg: AiConfig, ctx?: Context): Promise<string[]> {
  if (cfg.url.length === 0 || cfg.key.length === 0) {
    return Promise.reject(new Error('\u8BF7\u5148\u586B\u5199 API \u5730\u5740\u4E0E\u5BC6\u94A5'));
  }
  const proxy = proxyOpt(await loadProxy(ctx));
  const client = http.createHttp();
  try {
    const resp = await client.request(joinUrl(cfg.url, '/models'), {
      method: http.RequestMethod.GET,
      header: aiHeaders(cfg),
      connectTimeout: 15000,
      readTimeout: 20000,
      usingProxy: proxy
    });
    const text = resp.result as string;
    if (resp.responseCode < 200 || resp.responseCode >= 300) {
      throw new Error(classify(resp.responseCode) + ' HTTP ' + resp.responseCode + ': ' + parseErrorBody(text));
    }
    const o = JSON.parse(text) as Record<string, Object>;
    /* openai lists data[].id, gemini lists models[].name */
    let arr = o['data'] as Array<Record<string, Object>>;
    if (arr === undefined || arr === null) {
      arr = o['models'] as Array<Record<string, Object>>;
    }
    const ids: string[] = [];
    if (arr !== undefined && arr !== null) {
      for (let i = 0; i < arr.length; i++) {
        let id = arr[i]['id'];
        if (typeof id !== 'string' || id.length === 0) {
          id = arr[i]['name'];
        }
        if (typeof id === 'string' && id.length > 0) {
          /* Gemini lists entries as "models/<id>"; chat() builds
           * "/models/<id>" itself - storing the prefix made every model
           * picked from the list 404 (Android parity) */
          ids.push(id.startsWith('models/') ? id.substring(7) : id);
        }
      }
    }
    ids.sort((a: string, b: string): number => {
      const la = a.toLowerCase();
      const lb = b.toLowerCase();
      return la < lb ? -1 : (la > lb ? 1 : 0);
    });
    return ids;
  } finally {
    client.destroy();
  }
}

/** Quick connectivity test with a tiny prompt. */
export async function testChat(cfg: AiConfig, text: string, ctx?: Context): Promise<string> {
  const msgs: AiMessage[] = [{ role: 'user', content: text.length > 0 ? text : '\u4F60\u597D' }];
  return chatCompletion(cfg, msgs, 256, ctx);
}
