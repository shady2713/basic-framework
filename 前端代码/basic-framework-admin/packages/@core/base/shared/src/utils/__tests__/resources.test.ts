import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { loadScript } from '../resources';

const testJsPath = 'data:text/javascript,window.__load_script_test__=true;';
const duplicateJsPath =
  'data:text/javascript,window.__duplicate_script_test__=true;';
const errorJsPath = 'https://example.test/error.js';

let appendedScripts: HTMLScriptElement[] = [];

describe('loadScript', () => {
  beforeEach(() => {
    appendedScripts = [];
    vi.spyOn(document, 'querySelector').mockImplementation((selector) => {
      const match = /^script\[src="(.*)"\]$/.exec(selector);
      if (!match) {
        return null;
      }
      return (
        appendedScripts.find(
          (script) => script.getAttribute('src') === match[1],
        ) ?? null
      );
    });
    vi.spyOn(document.head, 'append').mockImplementation((...nodes) => {
      for (const node of nodes) {
        if (node instanceof HTMLScriptElement) {
          appendedScripts.push(node);
        }
      }
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should resolve when the script loads successfully', async () => {
    const promise = loadScript(testJsPath);

    const script = appendedScripts[0];
    if (!script) {
      throw new Error('Expected the script element to be appended');
    }

    script.dispatchEvent(new Event('load'));
    await expect(promise).resolves.toBeUndefined();
  });

  it('should not insert duplicate script and resolve immediately if already loaded', async () => {
    const existing = document.createElement('script');
    existing.src = duplicateJsPath;
    document.head.append(existing);

    const promise = loadScript(duplicateJsPath);
    await expect(promise).resolves.toBeUndefined();
    expect(appendedScripts).toEqual([existing]);
  });

  it('should reject when the script fails to load', async () => {
    const promise = loadScript(errorJsPath);

    const script = appendedScripts[0];
    if (!script) {
      throw new Error('Expected the script element to be appended');
    }

    script.dispatchEvent(new Event('error'));

    await expect(promise).rejects.toThrow(
      `Failed to load script: ${errorJsPath}`,
    );
  });

  it('should handle multiple concurrent calls and only insert one script tag', async () => {
    const p1 = loadScript(testJsPath);
    const p2 = loadScript(testJsPath);

    const script = appendedScripts[0];
    if (!script) {
      throw new Error('Expected the script element to be appended');
    }

    script.dispatchEvent(new Event('load'));

    await expect(p1).resolves.toBeUndefined();
    await expect(p2).resolves.toBeUndefined();

    expect(appendedScripts).toHaveLength(1);
  });
});
