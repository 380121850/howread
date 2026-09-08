import http from '@ohos.net.http';
import preferences from '@ohos.data.preferences';

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

/** Single chat completion (non-stream). Resolves with the assistant text. */
export async function chatCompletion(cfg: AiConfig, messages: AiMessage[], maxTokens: number): Promise<string> {
  if (!aiConfigReady(cfg)) {
    return Promise.reject(new Error('\u8BF7\u5148\u5728\u504F\u597D\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B'));
  }
  const bodyObj: Record<string, Object> = {
    'model': cfg.model,
    'messages': messages,
    'max_tokens': maxTokens > 0 ? maxTokens : cfg.maxTokens,
    'stream': false
  };
  if (cfg.provider === AI_PROVIDERS[0]) {
    const t: Record<string, string> = { 'type': cfg.thinking ? 'enabled' : 'disabled' };
    bodyObj['thinking'] = t;
  }
  const client = http.createHttp();
  try {
    const resp = await client.request(joinUrl(cfg.url, '/chat/completions'), {
      method: http.RequestMethod.POST,
      header: aiHeaders(cfg),
      extraData: JSON.stringify(bodyObj),
      connectTimeout: 15000,
      readTimeout: 60000
    });
    const text = resp.result as string;
    if (resp.responseCode < 200 || resp.responseCode >= 300) {
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
    return content;
  } finally {
    client.destroy();
  }
}

/** List model ids available for the key (GET /models). */
export async function listModels(cfg: AiConfig): Promise<string[]> {
  if (cfg.url.length === 0 || cfg.key.length === 0) {
    return Promise.reject(new Error('\u8BF7\u5148\u586B\u5199 API \u5730\u5740\u4E0E\u5BC6\u94A5'));
  }
  const client = http.createHttp();
  try {
    const resp = await client.request(joinUrl(cfg.url, '/models'), {
      method: http.RequestMethod.GET,
      header: aiHeaders(cfg),
      connectTimeout: 15000,
      readTimeout: 20000
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
        if (typeof id === 'string') {
          ids.push(id);
        }
      }
    }
    return ids;
  } finally {
    client.destroy();
  }
}

/** Quick connectivity test with a tiny prompt. */
export async function testChat(cfg: AiConfig, text: string): Promise<string> {
  const msgs: AiMessage[] = [{ role: 'user', content: text.length > 0 ? text : '\u4F60\u597D' }];
  return chatCompletion(cfg, msgs, 256);
}
