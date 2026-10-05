import fs from '@ohos.file.fs';
import { hilog } from '@kit.PerformanceAnalysisKit';

/**
 * 0.9.20: local crash traces (Android crash.txt parity). Appended records
 * survive system log rotation; the file is recreated once it exceeds 1MB.
 */

const CRASH_FILE: string = '/crash.txt';
const MAX_SIZE: number = 1024 * 1024;

export function writeCrashLog(filesDir: string, tag: string, detail: string): void {
  try {
    const path: string = filesDir + CRASH_FILE;
    try {
      const st: fs.Stat = fs.statSync(path);
      if (st.size > MAX_SIZE) {
        fs.unlinkSync(path);
      }
    } catch (e) { /* not there yet */ }
    const stamp: string = new Date().toISOString();
    const line: string = '==== ' + stamp + ' [' + tag + ']\n' + detail + '\n\n';
    const f: fs.File = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.APPEND);
    fs.writeSync(f.fd, line);
    fs.closeSync(f);
  } catch (e) {
    hilog.error(0x0000, 'Librera', 'crash log write failed: %{public}s', String(e));
  }
}
