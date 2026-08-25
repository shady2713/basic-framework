import CryptoJS from 'crypto-js';

import { logError } from './error';

const AES_IV_SIZE_BYTES = 16;
const AES_IV_SIZE_WORDS = AES_IV_SIZE_BYTES / 4;
const AES_KEY_LENGTHS = new Set([16, 24, 32]);

function assertAesKey(key: string, operation: string) {
  if (!key) {
    throw new Error(`AES ${operation}密钥不能为空`);
  }
  if (!AES_KEY_LENGTHS.has(key.length)) {
    throw new Error(
      `AES ${operation}密钥长度必须为 16、24 或 32 位，当前长度: ${key.length}`,
    );
  }
}

/**
 * 验证码协议使用的 AES-CBC 工具。密文格式为 Base64(16 字节随机 IV + 密文)。
 * 此工具不提供浏览器端密钥保密能力，不得用于替代 TLS 或持久化秘密。
 */
export const AES = {
  /**
   * @param data 要加密的数据
   * @param key 加密密钥
   * @returns 加密后的字符串
   */
  encrypt(data: string, key: string): string {
    try {
      assertAesKey(key, '加密');
      const keyUtf8 = CryptoJS.enc.Utf8.parse(key);
      const iv = CryptoJS.lib.WordArray.random(AES_IV_SIZE_BYTES);
      const encrypted = CryptoJS.AES.encrypt(data, keyUtf8, {
        iv,
        mode: CryptoJS.mode.CBC,
        padding: CryptoJS.pad.Pkcs7,
      });
      // eslint-disable-next-line unicorn/prefer-spread -- CryptoJS WordArray#concat is not Array#concat
      const payload = iv.clone().concat(encrypted.ciphertext);
      return CryptoJS.enc.Base64.stringify(payload);
    } catch (error) {
      logError('AES encrypt failed', error);
      throw error;
    }
  },

  /**
   * @param encryptedData 加密的数据
   * @param key 解密密钥
   * @returns 解密后的字符串
   */
  decrypt(encryptedData: string, key: string): string {
    try {
      assertAesKey(key, '解密');
      if (!encryptedData) {
        throw new Error('AES 解密数据不能为空');
      }

      const payload = CryptoJS.enc.Base64.parse(encryptedData);
      if (payload.sigBytes <= AES_IV_SIZE_BYTES) {
        throw new Error('AES 解密数据格式不正确');
      }

      const iv = CryptoJS.lib.WordArray.create(
        payload.words.slice(0, AES_IV_SIZE_WORDS),
        AES_IV_SIZE_BYTES,
      );
      const ciphertext = CryptoJS.lib.WordArray.create(
        payload.words.slice(AES_IV_SIZE_WORDS),
        payload.sigBytes - AES_IV_SIZE_BYTES,
      );
      const keyUtf8 = CryptoJS.enc.Utf8.parse(key);
      const decrypted = CryptoJS.AES.decrypt(
        CryptoJS.lib.CipherParams.create({ ciphertext }),
        keyUtf8,
        {
          iv,
          mode: CryptoJS.mode.CBC,
          padding: CryptoJS.pad.Pkcs7,
        },
      );
      const result = decrypted.toString(CryptoJS.enc.Utf8);
      if (!result) {
        throw new Error('AES 解密结果为空，可能是密钥错误或数据损坏');
      }
      return result;
    } catch (error) {
      logError('AES decrypt failed', error);
      throw error;
    }
  },
};
