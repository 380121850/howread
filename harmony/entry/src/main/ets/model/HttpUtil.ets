import connection from '@ohos.net.connection';
import preferences from '@ohos.data.preferences';

/**
 * Shared HTTP proxy helper (round 5).
 * Reads the proxy fields stored in ReaderSettings (prefs `librera_settings`)
 * directly to avoid import cycles, and builds the `usingProxy` option value
 * accepted by @ohos.net.http.
 */

const PREF_NAME = 'librera_settings';
const KEY_SETTINGS = 'settings';

/** Proxy configuration as persisted in ReaderSettings. */
export class ProxyInfo {
  host: string = '';
  port: number = 0;
  /** 0=none, 1=http, 2=socks5 */
  type: number = 0;
}

export async function loadProxy(ctx?: Context): Promise<ProxyInfo> {
  const info = new ProxyInfo();
  if (ctx === undefined) {
    return info;
  }
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_NAME);
    const json: string = await prefs.get(KEY_SETTINGS, '') as string;
    if (json.length === 0) {
      return info;
    }
    const raw = JSON.parse(json) as Record<string, Object>;
    info.host = typeof raw['proxyHost'] === 'string' ? raw['proxyHost'] as string : '';
    info.port = typeof raw['proxyPort'] === 'number' ? raw['proxyPort'] as number : 0;
    info.type = typeof raw['proxyType'] === 'number' ? raw['proxyType'] as number : 0;
  } catch (e) {
    /* no settings yet → no proxy */
  }
  return info;
}

/** Build the value for HttpRequestOptions.usingProxy from ProxyInfo. */
export function proxyFor(p: ProxyInfo): boolean | connection.HttpProxy {
  if (p.type === 0 || p.host.length === 0 || p.port <= 0) {
    return false;
  }
  const exclusions: Array<string> = [];
  const hp: connection.HttpProxy = {
    host: p.host,
    port: p.port,
    exclusionList: exclusions
  };
  return hp;
}

/** Convenience: proxy option directly from a context (undefined → false). */
export function proxyOpt(p: ProxyInfo | undefined): boolean | connection.HttpProxy {
  if (p === undefined) {
    return false;
  }
  return proxyFor(p);
}
