import fs from '@ohos.file.fs';
import preferences from '@ohos.data.preferences';
import cryptoFramework from '@ohos.security.cryptoFramework';

/**
 * Content MD5 of a book file (Android com.foobnix.android.utils.FileHash
 * parity): lowercase hex, streamed in 1 MiB chunks, cached per path by
 * size+mtime so unchanged multi-MB books are hashed only once. This hash is
 * the device-independent identity of a book in the WebDAV interop sync
 * (books/<hash>.json). '' when the file is unreadable or a remote:// path
 * (remote books join the sync once their whole-book cache exists).
 */
const PREF_NAME = 'librera_bookhash';

let prefInstance: preferences.Preferences | undefined = undefined;
let prefStore: string = '';

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined || prefStore !== PREF_NAME) {
    prefStore = PREF_NAME;
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

export async function bookMd5(ctx: Context, path: string): Promise<string> {
  try {
    if (path.length === 0 || path.indexOf('remote://') === 0) {
      return '';
    }
    if (!fs.accessSync(path)) {
      return '';
    }
    const st = fs.statSync(path);
    if (!st.isFile()) {
      return '';
    }
    const prefs = await getPrefs(ctx);
    const raw: string = await prefs.get(path, '') as string;
    if (raw.length > 0) {
      const rec = JSON.parse(raw) as Record<string, Object>;
      const sz = typeof rec['size'] === 'number' ? rec['size'] as number : -1;
      const mt = typeof rec['mtime'] === 'number' ? rec['mtime'] as number : -1;
      const hx = typeof rec['md5'] === 'string' ? rec['md5'] as string : '';
      if (sz === st.size && mt === st.mtime && hx.length === 32) {
        return hx;
      }
    }
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    const md = cryptoFramework.createMd('MD5');
    const CHUNK = 1048576;
    let off = 0;
    while (off < st.size) {
      const len = Math.min(CHUNK, st.size - off);
      const buf = new ArrayBuffer(len);
      fs.readSync(file.fd, buf, { offset: off });
      await md.update({ data: new Uint8Array(buf) });
      off += len;
    }
    fs.closeSync(file);
    const blob = await md.digest();
    const arr: Uint8Array = new Uint8Array(blob.data);
    let hex = '';
    for (let i = 0; i < arr.length; i++) {
      hex += arr[i].toString(16).padStart(2, '0');
    }
    const rec2: Record<string, Object> = { 'size': st.size, 'mtime': st.mtime, 'md5': hex };
    await prefs.put(path, JSON.stringify(rec2));
    await prefs.flush();
    return hex;
  } catch (e) {
    console.error('[BookHash] md5 failed: ' + path.substring(Math.max(0, path.length - 40)));
    return '';
  }
}
