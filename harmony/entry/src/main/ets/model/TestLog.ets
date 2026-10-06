import { hilog } from '@kit.PerformanceAnalysisKit';
import { debugLogWrite, debugLogIsOn } from './DebugLog';

/**
 * CI test instrumentation log (ci/autotest-hmos): unified TAG "HRTEST".
 * Message format "evt=<name> ts=<epoch-ms> <k=v ...>" — the ts payload makes
 * the test host immune to log-delivery latency when computing durations.
 * Deliberately kept in BOTH debug and release builds (event-level volume).
 */
export function tlog(evt: string, kv: string = ''): void {
  hilog.info(0x0000, 'HRTEST', 'evt=%{public}s ts=%{public}d %{public}s', evt, Date.now(), kv);
  /* 0.9.21: mirror event-level markers into the debug log file (Android
   * DebugLog tightened capture: events only, chatty logs stay in hilog). */
  if (debugLogIsOn()) {
    debugLogWrite('TLOG', 'evt=' + evt + ' ts=' + Date.now() + (kv.length > 0 ? ' ' + kv : ''));
  }
}
