import http from '@ohos.net.http';
import preferences from '@ohos.data.preferences';
import util from '@ohos.util';
import { loadProxy, proxyOpt } from './HttpUtil';
import { tlog } from './TestLog';

/**
 * OpenAI-compatible AI client (Android parity 2026-09: AI 大模型接入).
 * Config persisted via @ohos.data.preferences so both Index prefs and the
 * Reader translation panel share the same credentials.
 */

export interface AiConfig {
  /** vendor label: 智谱 / OpenAI / DeepSeek / 自定义 */
  provider: string;
  /** base URL, e.g. https://open.bigmodel.cn/api/paas/v4 */
  url: string;
  /** API key (Bearer) */
  key: string;
  /** model name, e.g. glm-4.5-air */
  model: string;
  /** max output tokens */
  maxTokens: number;
  /** thinking mode (智谱 thinking.type; ignored elsewhere) */
  thinking: boolean;
}

export const AI_PROVIDERS: string[] = ['\u667A\u8C31', 'OpenAI', 'DeepSeek', '\u81EA\u5B9A\u4E49'];
export const AI_PROVIDER_URLS: string[] = [
  'https://open.bigmodel.cn/api/paas/v4',
  'https://api.openai.com/v1',
  'https://api.deepseek.com/v1',
  ''
];

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

function aiHeaders(c: AiConfig): Record<string, string> {
  const h: Record<string, string> = {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + c.key
  };
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
  } catch (e) {
    /* fall through */
  }
  return body.length > 120 ? body.substring(0, 120) : body;
}

/** Single non-stream request attempt (withThinking gates the GLM thinking switch). */
async function chatOnce(cfg: AiConfig, messages: AiMessage[], maxTokens: number, ctx: Context | undefined, withThinking: boolean): Promise<string> {
const t0: number = Date.now();
tlog('ai_req_start', 'model=' + cfg.model);
  if (!aiConfigReady(cfg)) {
    return Promise.reject(new Error('\u8BF7\u5148\u5728\u504F\u597D\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B'));
  }
  const bodyObj: Record<string, Object> = {
    'model': cfg.model,
    'messages': messages,
    'max_tokens': maxTokens > 0 ? maxTokens : cfg.maxTokens,
    'stream': false
  };
  if (cfg.provider === AI_PROVIDERS[0] && withThinking) {
    const t: Record<string, string> = { 'type': cfg.thinking ? 'enabled' : 'disabled' };
    bodyObj['thinking'] = t;
  }
  const proxy = proxyOpt(await loadProxy(ctx));
  const client = http.createHttp();
  try {
    const resp = await client.request(joinUrl(cfg.url, '/chat/completions'), {
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
      throw new Error('HTTP ' + resp.responseCode + ': ' + parseErrorBody(text));
    }
    const o = JSON.parse(text) as Record<string, Object>;
    const choices = o['choices'] as Array<Record<string, Object>>;
    if (choices === undefined || choices === null || choices.length === 0) {
      throw new Error('\u6A21\u578B\u672A\u8FD4\u56DE\u5185\u5BB9');
    }
    const msg = choices[0]['message'] as Record<string, Object>;
    const content = msg !== undefined && msg !== null ? msg['content'] as string : '';
    if (content === undefined || content === null || content.length === 0) {
      throw new Error('\u6A21\u578B\u672A\u8FD4\u56DE\u5185\u5BB9');
    }
    tlog('ai_req_done', 'ms=' + (Date.now() - t0));
    return content;
  } finally {
    client.destroy();
  }
}

/** HTTP 400 helper (thinking rejection / fallback decisions). */
function isHttp400(e: Object): boolean {
  const msg = e instanceof Error ? e.message : String(e);
  return msg.indexOf('HTTP 400') === 0;
}

/** Single chat completion (non-stream). Retries once without the thinking
 * switch when the endpoint rejects it with 400, then remembers the combo. */
export async function chatCompletion(cfg: AiConfig, messages: AiMessage[], maxTokens: number, ctx?: Context): Promise<string> {
  await loadNoThinking(ctx);
  const withThinking = !NO_THINK.has(thinkKey(cfg));
  try {
    return await chatOnce(cfg, messages, maxTokens, ctx, withThinking);
  } catch (e) {
    if (withThinking && isHttp400(e as Object)) {
      await learnNoThinking(cfg, ctx);
      return await chatOnce(cfg, messages, maxTokens, ctx, false);
    }
    throw e as Error;
  }
}

/** Stream one request via SSE; onDelta delivers the accumulated text while
 * it grows. Rejects with "HTTP <code>: body" on non-2xx responses. */
async function streamOnce(cfg: AiConfig, messages: AiMessage[], maxTokens: number,
  onDelta: (full: string) => void, ctx: Context | undefined, withThinking: boolean,
  isCancelled?: () => boolean): Promise<string> {
const t0: number = Date.now();
tlog('ai_req_start', 'stream model=' + cfg.model);
  if (!aiConfigReady(cfg)) {
    return Promise.reject(new Error('\u8BF7\u5148\u5728\u504F\u597D\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B'));
  }
  const bodyObj: Record<string, Object> = {
    'model': cfg.model,
    'messages': messages,
    'max_tokens': maxTokens > 0 ? maxTokens : cfg.maxTokens,
    'stream': true
  };
  if (cfg.provider === AI_PROVIDERS[0] && withThinking) {
    const t: Record<string, string> = { 'type': cfg.thinking ? 'enabled' : 'disabled' };
    bodyObj['thinking'] = t;
  }
  const proxy = proxyOpt(await loadProxy(ctx));
  const client = http.createHttp();
  const pend: number[] = [];
  let sseBuf = '';
  let full = '';
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
                if (delta !== undefined && delta !== null && typeof delta['content'] === 'string') {
                  full += delta['content'] as string;
                  onDelta(full);
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
        finishErr('HTTP ' + code + ': ' + parseErrorBody(errBody));
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
              onDelta(content);
              finishOk(content);
              return;
            }
          }
        } catch (err) {
          /* fall through with the streamed text */
        }
      }
      finishOk(full);
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

/** Streaming chat completion. Retries without the thinking switch on a
 * thinking-caused 400, and falls back to the non-stream path when the
 * endpoint rejects streaming or the transport fails (Android 2026-09-24/25). */
export async function chatCompletionStream(cfg: AiConfig, messages: AiMessage[], maxTokens: number,
  onDelta: (full: string) => void, ctx?: Context, isCancelled?: () => boolean): Promise<string> {
  await loadNoThinking(ctx);
  const withThinking = !NO_THINK.has(thinkKey(cfg));
  try {
    return await streamOnce(cfg, messages, maxTokens, onDelta, ctx, withThinking, isCancelled);
  } catch (e) {
    if ((e instanceof Error ? e.message : String(e)) === 'CANCELLED') {
      throw e as Error;
    }
    if (withThinking && isHttp400(e as Object)) {
      await learnNoThinking(cfg, ctx);
      try {
        return await streamOnce(cfg, messages, maxTokens, onDelta, ctx, false, isCancelled);
      } catch (e2) {
        if ((e2 instanceof Error ? e2.message : String(e2)) === 'CANCELLED') {
          throw e2 as Error;
        }
        return await chatOnce(cfg, messages, maxTokens, ctx, false);
      }
    }
    /* streaming unsupported or transport failure -> whole-text fallback */
    return await chatCompletion(cfg, messages, maxTokens, ctx);
  }
}

/** List model ids available for the key (GET /models). */
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
      throw new Error('HTTP ' + resp.responseCode + ': ' + parseErrorBody(text));
    }
    const o = JSON.parse(text) as Record<string, Object>;
    const data = o['data'] as Array<Record<string, Object>>;
    const ids: string[] = [];
    if (data !== undefined && data !== null) {
      for (let i = 0; i < data.length; i++) {
        const id = data[i]['id'];
        if (typeof id === 'string' && id.length > 0) {
          /* Gemini-style model lists carry a redundant "models/" prefix
           * (Android 2026-09-13 fix parity) — strip it for direct use */
          ids.push(id.startsWith('models/') ? id.substring(7) : id);
        }
      }
    }
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
