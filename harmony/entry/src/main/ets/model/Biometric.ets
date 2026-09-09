import userAuth from '@ohos.userIAM.userAuth';

/**
 * Biometric unlock helper (round 5, Android BiometricPrompt parity).
 * Uses @ohos.userIAM.userAuth (FINGERPRINT preferred, FACE fallback).
 * Falls back gracefully when the hardware/credential is unavailable.
 */

/** True when fingerprint or face auth is enrolled and usable. */
export function biometricAvailable(): boolean {
  try {
    userAuth.getAvailableStatus(userAuth.UserAuthType.FINGERPRINT, userAuth.AuthTrustLevel.ATL1);
    return true;
  } catch (_e) {
    /* fingerprint not available → try face */
  }
  try {
    userAuth.getAvailableStatus(userAuth.UserAuthType.FACE, userAuth.AuthTrustLevel.ATL1);
    return true;
  } catch (_e) {
    return false;
  }
}

/** Which auth type is usable: 'fingerprint' | 'face' | '' (none). */
export function biometricType(): string {
  try {
    userAuth.getAvailableStatus(userAuth.UserAuthType.FINGERPRINT, userAuth.AuthTrustLevel.ATL1);
    return 'fingerprint';
  } catch (_e) {
    /* fall through */
  }
  try {
    userAuth.getAvailableStatus(userAuth.UserAuthType.FACE, userAuth.AuthTrustLevel.ATL1);
    return 'face';
  } catch (_e) {
    return '';
  }
}

/** Start a biometric auth session with the system user-auth widget. */
export function startBiometricAuth(onSuccess: () => void, onFail: (msg: string) => void): void {
  try {
    const t: string = biometricType();
    if (t.length === 0) {
      onFail('\u751F\u7269\u8BC6\u522B\u4E0D\u53EF\u7528');
      return;
    }
    const types: userAuth.UserAuthType[] = [];
    types.push(t === 'fingerprint' ? userAuth.UserAuthType.FINGERPRINT : userAuth.UserAuthType.FACE);
    const inst = userAuth.getUserAuthInstance({
      challenge: new Uint8Array(0),
      authType: types,
      authTrustLevel: userAuth.AuthTrustLevel.ATL1
    }, { title: '\u89E3\u9501 HowRead' });
    const cb: userAuth.IAuthCallback = {
      onResult: (r: userAuth.UserAuthResult) => {
        if (r.result === 0) {
          onSuccess();
        } else {
          onFail('\u9A8C\u8BC1\u5931\u8D25(' + r.result + ')');
        }
      }
    };
    inst.on('result', cb);
    inst.start();
  } catch (e) {
    onFail('\u751F\u7269\u8BC6\u522B\u4E0D\u53EF\u7528');
  }
}
